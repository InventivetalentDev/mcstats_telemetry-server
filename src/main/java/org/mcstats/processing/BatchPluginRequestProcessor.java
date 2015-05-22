package org.mcstats.processing;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.inject.Inject;
import org.apache.log4j.Logger;
import org.mcstats.AccumulatorDelegator;
import org.mcstats.MCStats;
import org.mcstats.accumulator.CustomDataAccumulator;
import org.mcstats.accumulator.MCStatsInfoAccumulator;
import org.mcstats.accumulator.ServerInfoAccumulator;
import org.mcstats.accumulator.VersionInfoAccumulator;
import org.mcstats.db.ModelCache;
import org.mcstats.db.RedisCache;
import org.mcstats.decoder.DecodedRequest;
import org.mcstats.handler.ReportHandler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import javax.inject.Singleton;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Singleton
public class BatchPluginRequestProcessor {

    private static final Logger logger = Logger.getLogger(BatchPluginRequestProcessor.class);

    /**
     * The maximum amount of allowable version switches in a graph interval before they are blacklisted;
     */
    private static final int MAX_VIOLATIONS_ALLOWED = 7;

    public static final int NUM_THREADS = 16;

    /**
     * The pool used to service requests
     */
    private final ExecutorService servicePool = Executors.newFixedThreadPool(NUM_THREADS, new ThreadFactoryBuilder().setNameFormat(BatchPluginRequestProcessor.class.getSimpleName() + "-%d").build());

    /**
     * The queue used for requests
     */
    private final Queue<DecodedRequest> queue = new LinkedBlockingQueue<>();

    /**
     * SHA of the redis sum add script. TODO better way of storing the SHAs rather than locally?
     */
    private final String redisAddSumScriptSha;

    /**
     * Flag for if the processor is running or not.
     */
    private boolean running = true;

    private final MCStats mcstats;
    private final Gson gson;
    private final RedisCache modelCache;
    private final JedisPool redisPool;
    private final AccumulatorDelegator accumulatorDelegator;

    @Inject
    public BatchPluginRequestProcessor(MCStats mcstats, Gson gson, ModelCache modelCache, JedisPool redisPool, AccumulatorDelegator accumulatorDelegator) {
        this.mcstats = mcstats;
        this.gson = gson;
        this.modelCache = (RedisCache) modelCache; // TODO inject directly?
        this.redisPool = redisPool;
        this.accumulatorDelegator = accumulatorDelegator;
        this.redisAddSumScriptSha = mcstats.loadRedisScript("/scripts/redis/zadd-sum.lua");

        // TODO add from somewhere else
        accumulatorDelegator.add(new ServerInfoAccumulator(mcstats));
        accumulatorDelegator.add(new MCStatsInfoAccumulator());
        accumulatorDelegator.add(new VersionInfoAccumulator());
        accumulatorDelegator.add(new CustomDataAccumulator());

        for (int i = 0; i < NUM_THREADS; i++) {
            servicePool.execute(new Worker());
        }
    }

    /**
     * Submits a request to the processor
     *
     * @param request
     */
    public void submit(DecodedRequest request) {
        queue.add(request);
    }

    /**
     * Gets the number of requests waiting to be processed.
     *
     * @return
     */
    public int size() {
        return queue.size();
    }

    /**
     * Shuts down the processor
     */
    public void shutdown() {
        running = false;
        servicePool.shutdown();
    }

    private final class Worker implements Runnable {
        @Override
        public void run() {
            while (running) {
                try (Jedis redis = redisPool.getResource()) {
                    Pipeline pipeline = redis.pipelined();

                    // max to process at one time
                    int remaining = 1000;
                    int processed = 0;

                    // Bucket: time segment data is generated for
                    int bucket = ReportHandler.normalizeTime();

                    while (!queue.isEmpty() && --remaining >= 0) {
                        DecodedRequest request = queue.poll();

                        if (request == null) {
                            continue;
                        }

                        // Plugin versions added to a set so that they can be calculated
                        // without requiring early accumulation or storing the entire
                        // ServerPlugin in redis
                        final String pluginVersionKey = "plugin-version-bucket:" + bucket + ":" + request.uuid + ":" + request.plugin;
                        pipeline.sadd(pluginVersionKey, request.pluginVersion);

                        final String pluginsKey = "plugins-bucket:" + bucket;
                        pipeline.sadd(pluginsKey, Integer.toString(request.plugin));

                        final String pluginBucketKey = "plugin-data-bucket:" + bucket + ":" + request.plugin;
                        pipeline.hset(pluginBucketKey, request.uuid, gson.toJson(request));

                        processed++;
                    }

                    if (processed > 0) {
                        logger.debug("Processed " + processed + " requests");
                    }

                    pipeline.sync();
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    logger.debug("Interrupted!", e);
                    break;
                }

            }
        }
    }

}
