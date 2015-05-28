package org.mcstats.aws.sqs;

import com.google.gson.Gson;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashMap;
import java.util.Map;

public class SQSWorkQueueClient {

    private final Gson gson;
    private final SimpleSQSClient sqs;
    private final String workQueueUrl;

    @Inject
    public SQSWorkQueueClient(Gson gson,
                              SimpleSQSClient sqs,
                              @Named("sqs.work-queue") String workQueueName) {
        this.gson = gson;
        this.sqs = sqs;
        workQueueUrl = sqs.createQueue(workQueueName);
    }

    /**
     * Queues the given bucket for accumulation
     *
     * @param bucket
     */
    public void accumulateBucket(int bucket) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "plugin");
        body.put("action", "accumulate");
        body.put("bucket", bucket);

        sqs.sendMessage(workQueueUrl, gson.toJson(body));
    }

    /**
     * Queues the given bucket for generation
     *
     * @param bucket
     */
    public void generateBucket(int bucket) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "plugin");
        body.put("action", "generate");
        body.put("bucket", bucket);

        sqs.sendMessage(workQueueUrl, gson.toJson(body));
    }

}
