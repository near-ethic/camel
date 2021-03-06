/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.sjms.batch;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultConsumer;
import org.apache.camel.processor.aggregate.AggregationStrategy;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SjmsBatchConsumer extends DefaultConsumer {

    public static final String SJMS_BATCH_TIMEOUT_CHECKER = "SJmsBatchTimeoutChecker";

    private static final boolean TRANSACTED = true;
    private static final Logger LOG = LoggerFactory.getLogger(SjmsBatchConsumer.class);

    // global counters, maybe they should be on component instead?
    private static final AtomicInteger BATCH_COUNT = new AtomicInteger();
    private static final AtomicLong MESSAGE_RECEIVED = new AtomicLong();
    private static final AtomicLong MESSAGE_PROCESSED = new AtomicLong();

    private ScheduledExecutorService timeoutCheckerExecutorService;
    private boolean shutdownTimeoutCheckerExecutorService;

    private final SjmsBatchEndpoint sjmsBatchEndpoint;
    private final AggregationStrategy aggregationStrategy;
    private final int completionSize;
    private final int completionInterval;
    private final int completionTimeout;
    private final int consumerCount;
    private final int pollDuration;
    private final ConnectionFactory connectionFactory;
    private final String destinationName;
    private final Processor processor;
    private ExecutorService jmsConsumerExecutors;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<CountDownLatch> consumersShutdownLatchRef = new AtomicReference<>();
    private Connection connection;

    public SjmsBatchConsumer(SjmsBatchEndpoint sjmsBatchEndpoint, Processor processor) {
        super(sjmsBatchEndpoint, processor);

        this.sjmsBatchEndpoint = ObjectHelper.notNull(sjmsBatchEndpoint, "batchJmsEndpoint");
        this.processor = ObjectHelper.notNull(processor, "processor");

        destinationName = ObjectHelper.notEmpty(sjmsBatchEndpoint.getDestinationName(), "destinationName");

        completionSize = sjmsBatchEndpoint.getCompletionSize();
        completionInterval = sjmsBatchEndpoint.getCompletionInterval();
        completionTimeout = sjmsBatchEndpoint.getCompletionTimeout();
        if (completionInterval > 0 && completionTimeout != SjmsBatchEndpoint.DEFAULT_COMPLETION_TIMEOUT) {
            throw new IllegalArgumentException("Only one of completionInterval or completionTimeout can be used, not both.");
        }
        if (sjmsBatchEndpoint.isSendEmptyMessageWhenIdle() && completionTimeout <= 0 && completionInterval <= 0) {
            throw new IllegalArgumentException("SendEmptyMessageWhenIdle can only be enabled if either completionInterval or completionTimeout is also set");
        }

        pollDuration = sjmsBatchEndpoint.getPollDuration();
        if (pollDuration < 0) {
            throw new IllegalArgumentException("pollDuration must be 0 or greater");
        }

        this.aggregationStrategy = ObjectHelper.notNull(sjmsBatchEndpoint.getAggregationStrategy(), "aggregationStrategy");

        consumerCount = sjmsBatchEndpoint.getConsumerCount();
        if (consumerCount <= 0) {
            throw new IllegalArgumentException("consumerCount must be greater than 0");
        }

        SjmsBatchComponent sjmsBatchComponent = (SjmsBatchComponent) sjmsBatchEndpoint.getComponent();
        connectionFactory = ObjectHelper.notNull(sjmsBatchComponent.getConnectionFactory(), "jmsBatchComponent.connectionFactory");
    }

    @Override
    public SjmsBatchEndpoint getEndpoint() {
        return sjmsBatchEndpoint;
    }

    public ScheduledExecutorService getTimeoutCheckerExecutorService() {
        return timeoutCheckerExecutorService;
    }

    public void setTimeoutCheckerExecutorService(ScheduledExecutorService timeoutCheckerExecutorService) {
        this.timeoutCheckerExecutorService = timeoutCheckerExecutorService;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        // start up a shared connection
        connection = connectionFactory.createConnection();
        connection.start();

        if (LOG.isInfoEnabled()) {
            LOG.info("Starting " + consumerCount + " consumer(s) for " + destinationName + ":" + completionSize);
        }
        consumersShutdownLatchRef.set(new CountDownLatch(consumerCount));

        jmsConsumerExecutors = getEndpoint().getCamelContext().getExecutorServiceManager().newFixedThreadPool(this, "SjmsBatchConsumer", consumerCount);

        final List<AtomicBoolean> triggers = new ArrayList<>();
        for (int i = 0; i < consumerCount; i++) {
            BatchConsumptionLoop loop = new BatchConsumptionLoop();
            triggers.add(loop.getCompletionTimeoutTrigger());
            jmsConsumerExecutors.execute(loop);
        }

        if (completionInterval > 0) {
            LOG.info("Using CompletionInterval to run every " + completionInterval + " millis.");
            if (timeoutCheckerExecutorService == null) {
                setTimeoutCheckerExecutorService(getEndpoint().getCamelContext().getExecutorServiceManager().newScheduledThreadPool(this, SJMS_BATCH_TIMEOUT_CHECKER, 1));
                shutdownTimeoutCheckerExecutorService = true;
            }
            // trigger completion based on interval
            timeoutCheckerExecutorService.scheduleAtFixedRate(new CompletionIntervalTask(triggers), completionInterval, completionInterval, TimeUnit.MILLISECONDS);
        }

    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        running.set(false);

        CountDownLatch consumersShutdownLatch = consumersShutdownLatchRef.get();
        if (consumersShutdownLatch != null) {
            LOG.info("Stop signalled, waiting on consumers to shut down");
            if (consumersShutdownLatch.await(60, TimeUnit.SECONDS)) {
                LOG.warn("Timeout waiting on consumer threads to signal completion - shutting down");
            } else {
                LOG.info("All consumers have shut down");
            }
        } else {
            LOG.info("Stop signalled while there are no consumers yet, so no need to wait for consumers");
        }

        try {
            LOG.debug("Shutting down JMS connection");
            connection.close();
        } catch (Exception e) {
            LOG.warn("Exception caught closing JMS connection", e);
        }

        getEndpoint().getCamelContext().getExecutorServiceManager().shutdown(jmsConsumerExecutors);
        jmsConsumerExecutors = null;

        if (shutdownTimeoutCheckerExecutorService) {
            getEndpoint().getCamelContext().getExecutorServiceManager().shutdownNow(timeoutCheckerExecutorService);
            timeoutCheckerExecutorService = null;
        }
    }

    /**
     * Background task that triggers completion based on interval.
     */
    private final class CompletionIntervalTask implements Runnable {

        private final List<AtomicBoolean> triggers;

        CompletionIntervalTask(List<AtomicBoolean> triggers) {
            this.triggers = triggers;
        }

        public void run() {
            // only run if CamelContext has been fully started
            if (!getEndpoint().getCamelContext().getStatus().isStarted()) {
                LOG.trace("Completion interval task cannot start due CamelContext({}) has not been started yet", getEndpoint().getCamelContext().getName());
                return;
            }

            // signal
            for (AtomicBoolean trigger : triggers) {
                trigger.set(true);
            }
        }
    }

    private class BatchConsumptionLoop implements Runnable {

        private final AtomicBoolean completionTimeoutTrigger = new AtomicBoolean();
        private final BatchConsumptionTask task = new BatchConsumptionTask(completionTimeoutTrigger);

        public AtomicBoolean getCompletionTimeoutTrigger() {
            return completionTimeoutTrigger;
        }

        @Override
        public void run() {
            try {
                // a batch corresponds to a single session that will be committed or rolled back by a background thread
                final Session session = connection.createSession(TRANSACTED, Session.CLIENT_ACKNOWLEDGE);
                try {
                    // only batch consumption from queues is supported - it makes no sense to transactionally consume
                    // from a topic as you don't car about message loss, users can just use a regular aggregator instead
                    Queue queue = session.createQueue(destinationName);
                    MessageConsumer consumer = session.createConsumer(queue);

                    try {
                        task.consumeBatchesOnLoop(session, consumer);
                    } finally {
                        try {
                            consumer.close();
                        } catch (JMSException ex2) {
                            // only include stacktrace in debug logging
                            if (log.isDebugEnabled()) {
                                log.debug("Exception caught closing consumer", ex2);
                            }
                            log.warn("Exception caught closing consumer: {}", ex2.getMessage());
                        }
                    }
                } finally {
                    try {
                        session.close();
                    } catch (JMSException ex1) {
                        // only include stacktrace in debug logging
                        if (log.isDebugEnabled()) {
                            log.debug("Exception caught closing session: {}", ex1);
                        }
                        log.warn("Exception caught closing session: {}", ex1.getMessage());
                    }
                }
            } catch (JMSException ex) {
                // from loop
                LOG.warn("Exception caught consuming from " + destinationName, ex);
            } finally {
                // indicate that we have shut down
                CountDownLatch consumersShutdownLatch = consumersShutdownLatchRef.get();
                consumersShutdownLatch.countDown();
            }
        }

        private final class BatchConsumptionTask {

            // state
            private final AtomicBoolean timeoutInterval;
            private final AtomicBoolean timeout = new AtomicBoolean();
            private int messageCount;
            private long timeElapsed;
            private long startTime;
            private Exchange aggregatedExchange;

            BatchConsumptionTask(AtomicBoolean timeoutInterval) {
                this.timeoutInterval = timeoutInterval;
            }

            private void consumeBatchesOnLoop(final Session session, final MessageConsumer consumer) throws JMSException {
                final boolean usingTimeout = completionTimeout > 0;

                LOG.trace("BatchConsumptionTask +++ start +++");

                while (running.get()) {

                    LOG.trace("BatchConsumptionTask running");

                    if (timeout.compareAndSet(true, false) || timeoutInterval.compareAndSet(true, false)) {
                        // trigger timeout
                        LOG.trace("Completion batch due timeout");
                        completionBatch(session);
                        reset();
                        continue;
                    }

                    if (completionSize > 0 && messageCount >= completionSize) {
                        // trigger completion size
                        LOG.trace("Completion batch due size");
                        completionBatch(session);
                        reset();
                        continue;
                    }

                    // check periodically to see whether we should be shutting down
                    long waitTime = (usingTimeout && (timeElapsed > 0))
                            ? getReceiveWaitTime(timeElapsed)
                            : pollDuration;
                    Message message = consumer.receive(waitTime);

                    if (running.get()) {
                        // no interruptions received
                        if (message == null) {
                            // timed out, no message received
                            LOG.trace("No message received");
                        } else {
                            messageCount++;
                            LOG.debug("#{} messages received", messageCount);

                            if (usingTimeout && startTime == 0) {
                                // this is the first message start counting down the period for this batch
                                startTime = new Date().getTime();
                            }

                            final Exchange exchange = getEndpoint().createExchange(message, session);
                            aggregatedExchange = aggregationStrategy.aggregate(aggregatedExchange, exchange);
                            aggregatedExchange.setProperty(Exchange.BATCH_SIZE, messageCount);
                        }

                        if (usingTimeout && startTime > 0) {
                            // a batch has been started, check whether it should be timed out
                            long currentTime = new Date().getTime();
                            timeElapsed = currentTime - startTime;

                            if (timeElapsed > completionTimeout) {
                                // batch finished by timeout
                                timeout.set(true);
                            } else {
                                LOG.trace("This batch has more time until the timeout, elapsed: {} timeout: {}", timeElapsed, completionTimeout);
                            }
                        }

                    } else {
                        LOG.info("Shutdown signal received - rolling back batch");
                        session.rollback();
                    }
                }

                LOG.trace("BatchConsumptionTask +++ end +++");
            }

            private void reset() {
                messageCount = 0;
                timeElapsed = 0;
                startTime = 0;
                aggregatedExchange = null;
            }

            private void completionBatch(final Session session) {
                // batch
                if (aggregatedExchange == null && getEndpoint().isSendEmptyMessageWhenIdle()) {
                    processEmptyMessage();
                } else if (aggregatedExchange != null) {
                    processBatch(aggregatedExchange, session);
                }
            }

        }

        /**
         * Determine the time that a call to {@link MessageConsumer#receive()} should wait given the time that has elapsed for this batch.
         *
         * @param timeElapsed The time that has elapsed.
         * @return The shorter of the time remaining or poll duration.
         */
        private long getReceiveWaitTime(long timeElapsed) {
            long timeRemaining = getTimeRemaining(timeElapsed);

            // wait for the shorter of the time remaining or the poll duration
            if (timeRemaining <= 0) { // ensure that the thread doesn't wait indefinitely
                timeRemaining = 1;
            }
            final long waitTime = Math.min(timeRemaining, pollDuration);

            LOG.trace("Waiting for {}", waitTime);
            return waitTime;
        }

        private long getTimeRemaining(long timeElapsed) {
            long timeRemaining = completionTimeout - timeElapsed;
            if (LOG.isDebugEnabled() && timeElapsed > 0) {
                LOG.debug("Time remaining this batch: {}", timeRemaining);
            }
            return timeRemaining;
        }

        /**
         * No messages in batch so send an empty message instead.
         */
        private void processEmptyMessage() {
            Exchange exchange = getEndpoint().createExchange();
            log.debug("Sending empty message as there were no messages from polling: {}", getEndpoint());
            try {
                getProcessor().process(exchange);
            } catch (Exception e) {
                getExceptionHandler().handleException("Error processing exchange", exchange, e);
            }
        }

        /**
         * Send an message with the batches messages.
         */
        private void processBatch(Exchange exchange, Session session) {
            int id = BATCH_COUNT.getAndIncrement();
            int batchSize = exchange.getProperty(Exchange.BATCH_SIZE, Integer.class);
            if (LOG.isDebugEnabled()) {
                long total = MESSAGE_RECEIVED.get() + batchSize;
                LOG.debug("Processing batch[" + id + "]:size=" + batchSize + ":total=" + total);
            }

            SessionCompletion sessionCompletion = new SessionCompletion(session);
            exchange.addOnCompletion(sessionCompletion);
            try {
                processor.process(exchange);
                long total = MESSAGE_PROCESSED.addAndGet(batchSize);
                LOG.debug("Completed processing[{}]:total={}", id, total);
            } catch (Exception e) {
                getExceptionHandler().handleException("Error processing exchange", exchange, e);
            }
        }

    }
}
