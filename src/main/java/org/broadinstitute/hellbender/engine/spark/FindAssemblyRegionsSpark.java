package org.broadinstitute.hellbender.engine.spark;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMSequenceDictionary;
import org.apache.spark.SparkFiles;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.broadinstitute.hellbender.engine.*;
import org.broadinstitute.hellbender.tools.DownsampleableSparkReadShard;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.activityprofile.ActivityProfileState;
import org.broadinstitute.hellbender.utils.activityprofile.ActivityProfileStateRange;
import org.broadinstitute.hellbender.utils.downsampling.PositionalDownsampler;
import org.broadinstitute.hellbender.utils.downsampling.ReadsDownsampler;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import scala.Tuple2;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

public class FindAssemblyRegionsSpark {

    public static JavaRDD<AssemblyRegionWalkerContext> getAssemblyRegionsFast(
            final JavaSparkContext ctx,
            final JavaRDD<GATKRead> reads,
            final SAMFileHeader header,
            final SAMSequenceDictionary sequenceDictionary,
            final String referenceFileName,
            final FeatureManager features,
            final List<ShardBoundary> intervalShards,
            final Broadcast<Supplier<AssemblyRegionEvaluator>> assemblyRegionEvaluatorSupplierBroadcast,
            final AssemblyRegionReadShardArgumentCollection shardingArgs,
            final AssemblyRegionArgumentCollection assemblyRegionArgs,
            final boolean includeReadsWithDeletionsInIsActivePileups,
            final boolean shuffle) {
        JavaRDD<Shard<GATKRead>> shardedReads = SparkSharder.shard(ctx, reads, GATKRead.class, sequenceDictionary, intervalShards, shardingArgs.readShardSize, shuffle);
        Broadcast<FeatureManager> bFeatureManager = features == null ? null : ctx.broadcast(features);
        return shardedReads.mapPartitions(getAssemblyRegionsFunctionFast(referenceFileName, bFeatureManager, header,
                assemblyRegionEvaluatorSupplierBroadcast, assemblyRegionArgs, includeReadsWithDeletionsInIsActivePileups));
    }

    private static FlatMapFunction<Iterator<Shard<GATKRead>>, AssemblyRegionWalkerContext> getAssemblyRegionsFunctionFast(
            final String referenceFileName,
            final Broadcast<FeatureManager> bFeatureManager,
            final SAMFileHeader header,
            final Broadcast<Supplier<AssemblyRegionEvaluator>> supplierBroadcast,
            final AssemblyRegionArgumentCollection assemblyRegionArgs,
            final boolean includeReadsWithDeletionsInIsActivePileups) {
        return (FlatMapFunction<Iterator<Shard<GATKRead>>, AssemblyRegionWalkerContext>) shardedReadIterator -> {
            ReferenceDataSource reference = referenceFileName == null ? null : new ReferenceFileSource(IOUtils.getPath(SparkFiles.get(referenceFileName)));
            final FeatureManager features = bFeatureManager == null ? null : bFeatureManager.getValue();
            AssemblyRegionEvaluator assemblyRegionEvaluator = supplierBroadcast.getValue().get(); // one AssemblyRegionEvaluator instance per Spark partition
            final ReadsDownsampler readsDownsampler = assemblyRegionArgs.maxReadsPerAlignmentStart > 0 ?
                    new PositionalDownsampler(assemblyRegionArgs.maxReadsPerAlignmentStart, header) : null;

            Iterator<Iterator<AssemblyRegionWalkerContext>> iterators = Utils.stream(shardedReadIterator)
                    .map(shardedRead -> new ShardToMultiIntervalShardAdapter<>(
                            new DownsampleableSparkReadShard(
                                    new ShardBoundary(shardedRead.getInterval(), shardedRead.getPaddedInterval()), shardedRead, readsDownsampler)))
                    .map(shardedRead -> {
                        final Iterator<AssemblyRegion> assemblyRegionIter = new AssemblyRegionIterator(
                                new ShardToMultiIntervalShardAdapter<>(shardedRead),
                                header, reference, features, assemblyRegionEvaluator,
                                assemblyRegionArgs.minAssemblyRegionSize, assemblyRegionArgs.maxAssemblyRegionSize,
                                assemblyRegionArgs.assemblyRegionPadding, assemblyRegionArgs.activeProbThreshold,
                                assemblyRegionArgs.maxProbPropagationDistance, includeReadsWithDeletionsInIsActivePileups);
                        return Utils.stream(assemblyRegionIter).map(assemblyRegion ->
                                new AssemblyRegionWalkerContext(assemblyRegion,
                                        new ReferenceContext(reference, assemblyRegion.getExtendedSpan()),
                                        new FeatureContext(features, assemblyRegion.getExtendedSpan()))).iterator();
                    }).iterator();
            return Iterators.concat(iterators);
        };
    }

    public static JavaRDD<AssemblyRegionWalkerContext> getAssemblyRegionsStrict(
            final JavaSparkContext ctx,
            final JavaRDD<GATKRead> reads,
            final SAMFileHeader header,
            final SAMSequenceDictionary sequenceDictionary,
            final String referenceFileName,
            final FeatureManager features,
            final List<ShardBoundary> intervalShards,
            final Broadcast<Supplier<AssemblyRegionEvaluator>> assemblyRegionEvaluatorSupplierBroadcast,
            final AssemblyRegionReadShardArgumentCollection shardingArgs,
            final AssemblyRegionArgumentCollection assemblyRegionArgs,
            final boolean includeReadsWithDeletionsInIsActivePileups,
            final boolean shuffle) {
        JavaRDD<Shard<GATKRead>> shardedReads = SparkSharder.shard(ctx, reads, GATKRead.class, sequenceDictionary, intervalShards, shardingArgs.readShardSize, shuffle);
        Broadcast<FeatureManager> bFeatureManager = features == null ? null : ctx.broadcast(features);

        // 1. Calculate activity for each locus in the desired intervals, in parallel.
        JavaRDD<ActivityProfileStateRange> activityProfileStates = shardedReads.mapPartitions(getActivityProfileStatesFunction(referenceFileName, bFeatureManager, header,
                assemblyRegionEvaluatorSupplierBroadcast, assemblyRegionArgs, includeReadsWithDeletionsInIsActivePileups));

        // 2. Group by contig. We need to do this so we can perform the band pass filter over the whole contig, so we
        // produce assembly regions that are identical to those produced by AssemblyRegionWalker.
        // This step requires a shuffle, but the amount of data in the ActivityProfileStateRange should be small, so it
        // should not be prohibitive.
        JavaPairRDD<String, Iterable<ActivityProfileStateRange>> contigToGroupedStates = activityProfileStates
                .keyBy((Function<ActivityProfileStateRange, String>) range -> range.getContig())
                .groupByKey();

        // 3. Run the band pass filter to find AssemblyRegions. The filtering is fairly cheap, so should be fast
        // even though it has to scan a whole contig. Note that we *don't* fill in reads here, since after we have found
        // the assembly regions we want to do assembly using the full resources of the cluster. So if we have
        // very small assembly region objects, then we can collect them on the driver (or repartition them)
        // for redistribution across the cluster, at which points the reads can be filled in. (See next two steps.)
        JavaRDD<ReadlessAssemblyRegion> readlessAssemblyRegions = contigToGroupedStates
                .flatMap(getReadlessAssemblyRegionsFunction(header, assemblyRegionArgs, includeReadsWithDeletionsInIsActivePileups));

        // 4. Pull the assembly region boundaries down to the driver, so we can fill in reads.
        List<ShardBoundary> assemblyRegionBoundaries = readlessAssemblyRegions
                .map((Function<ReadlessAssemblyRegion, ShardBoundary>) FindAssemblyRegionsSpark::toShardBoundary)
                .collect();

        // 5. Fill in the reads. Each shard is an assembly region, with its overlapping reads.
        JavaRDD<Shard<GATKRead>> assemblyRegionShardedReads = SparkSharder.shard(ctx, reads, GATKRead.class, header.getSequenceDictionary(), assemblyRegionBoundaries, shardingArgs.readShardSize);

        // 6. Convert shards to assembly regions.
        JavaRDD<AssemblyRegion> assemblyRegions = assemblyRegionShardedReads.map((Function<Shard<GATKRead>, AssemblyRegion>) shard -> toAssemblyRegion(shard, header));

        // 7. Add reference and feature context.
        return assemblyRegions.mapPartitions(getAssemblyRegionWalkerContextFunction(referenceFileName, bFeatureManager));
    }

    private static FlatMapFunction<Iterator<Shard<GATKRead>>, ActivityProfileStateRange> getActivityProfileStatesFunction(
            final String referenceFileName,
            final Broadcast<FeatureManager> bFeatureManager,
            final SAMFileHeader header,
            final Broadcast<Supplier<AssemblyRegionEvaluator>> supplierBroadcast,
            final AssemblyRegionArgumentCollection assemblyRegionArgs,
            final boolean includeReadsWithDeletionsInIsActivePileups) {
        return (FlatMapFunction<Iterator<Shard<GATKRead>>, ActivityProfileStateRange>) shardedReadIterator -> {
            ReferenceDataSource reference = referenceFileName == null ? null : new ReferenceFileSource(IOUtils.getPath(SparkFiles.get(referenceFileName)));
            final FeatureManager features = bFeatureManager == null ? null : bFeatureManager.getValue();
            AssemblyRegionEvaluator assemblyRegionEvaluator = supplierBroadcast.getValue().get(); // one AssemblyRegionEvaluator instance per Spark partition
            final ReadsDownsampler readsDownsampler = assemblyRegionArgs.maxReadsPerAlignmentStart > 0 ?
                    new PositionalDownsampler(assemblyRegionArgs.maxReadsPerAlignmentStart, header) : null;

            return Utils.stream(shardedReadIterator)
                    .map(shardedRead -> new ShardToMultiIntervalShardAdapter<>(shardedRead))
                    // TODO: reinstate downsampling (not yet working)
//                            new DownsampleableSparkReadShard(
//                                    new ShardBoundary(shardedRead.getInterval(), shardedRead.getPaddedInterval()), shardedRead, readsDownsampler)))
                    .map(shardedRead -> {
                        final Iterator<ActivityProfileState> activityProfileStateIter = new ActivityProfileStateIterator(
                                new ShardToMultiIntervalShardAdapter<>(shardedRead),
                                header, reference, features, assemblyRegionEvaluator,
                                includeReadsWithDeletionsInIsActivePileups);
                        return new ActivityProfileStateRange(shardedRead, activityProfileStateIter);
                    }).iterator();
        };
    }

    private static FlatMapFunction<Tuple2<String, Iterable<ActivityProfileStateRange>>, ReadlessAssemblyRegion> getReadlessAssemblyRegionsFunction(
            final SAMFileHeader header,
            final AssemblyRegionArgumentCollection assemblyRegionArgs,
            final boolean includeReadsWithDeletionsInIsActivePileups) {
        return (FlatMapFunction<Tuple2<String, Iterable<ActivityProfileStateRange>>, ReadlessAssemblyRegion>) iter ->
                Iterators.transform(
                        new AssemblyRegionFromActivityProfileStateIterator(
                                ActivityProfileStateRange.toIteratorActivityProfileState(iter._2.iterator()),
                                header,
                                assemblyRegionArgs.minAssemblyRegionSize,
                                assemblyRegionArgs.maxAssemblyRegionSize,
                                assemblyRegionArgs.assemblyRegionPadding,
                                assemblyRegionArgs.activeProbThreshold,
                                assemblyRegionArgs.maxProbPropagationDistance,
                                includeReadsWithDeletionsInIsActivePileups), new com.google.common.base.Function<AssemblyRegion, ReadlessAssemblyRegion>() {
                            @Nullable
                            @Override
                            public ReadlessAssemblyRegion apply(@Nullable AssemblyRegion input) {
                                return new ReadlessAssemblyRegion(input);
                            }
                        });
    }

    private static ShardBoundary toShardBoundary(ReadlessAssemblyRegion assemblyRegion) {
        return assemblyRegion;
    }

    private static AssemblyRegion toAssemblyRegion(Shard<GATKRead> shard, SAMFileHeader header) {
        // TODO: interfaces could be improved to avoid casting
        ReadlessAssemblyRegion readlessAssemblyRegion = (ReadlessAssemblyRegion) ((ShardBoundaryShard<GATKRead>) shard).getShardBoundary();
        int extension = Math.max(shard.getInterval().getStart() - shard.getPaddedInterval().getStart(), shard.getPaddedInterval().getEnd() - shard.getInterval().getEnd());
        AssemblyRegion assemblyRegion = new AssemblyRegion(shard.getInterval(), Collections.emptyList(), readlessAssemblyRegion.isActive(), extension, header);
        assemblyRegion.addAll(Lists.newArrayList(shard));
        return assemblyRegion;
    }

    private static FlatMapFunction<Iterator<AssemblyRegion>, AssemblyRegionWalkerContext> getAssemblyRegionWalkerContextFunction(
            final String referenceFileName,
            final Broadcast<FeatureManager> bFeatureManager) {

        return (FlatMapFunction<Iterator<AssemblyRegion>, AssemblyRegionWalkerContext>) assemblyRegionIter -> {
            ReferenceDataSource reference = referenceFileName == null ? null : new ReferenceFileSource(IOUtils.getPath(SparkFiles.get(referenceFileName)));
            final FeatureManager features = bFeatureManager == null ? null : bFeatureManager.getValue();
            return Utils.stream(assemblyRegionIter).map(assemblyRegion ->
                    new AssemblyRegionWalkerContext(assemblyRegion,
                            new ReferenceContext(reference, assemblyRegion.getExtendedSpan()),
                            new FeatureContext(features, assemblyRegion.getExtendedSpan()))).iterator();
        };
    }
}