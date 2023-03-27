/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.exchange;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ListenableActionFuture;
import org.elasticsearch.compute.data.Page;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link ExchangeSinkHandler} receives pages and status from its {@link ExchangeSink}s, which are created using
 * {@link #createExchangeSink()}} method. Pages and status can then be retrieved asynchronously by {@link ExchangeSourceHandler}s
 * using the {@link #fetchPageAsync(ExchangeRequest, ActionListener)} method.
 *
 * @see #createExchangeSink()
 * @see #fetchPageAsync(ExchangeRequest, ActionListener)
 * @see ExchangeSourceHandler
 */
public final class ExchangeSinkHandler {
    private final ExchangeBuffer buffer;
    private final Queue<ActionListener<ExchangeResponse>> listeners = new ConcurrentLinkedQueue<>();
    private final AtomicInteger outstandingSinks = new AtomicInteger();
    private volatile boolean allSourcesFinished = false;
    // listeners are notified by only one thread.
    private final Semaphore promised = new Semaphore(1);

    public ExchangeSinkHandler(int maxBufferSize) {
        this.buffer = new ExchangeBuffer(maxBufferSize);
    }

    private class LocalExchangeSink implements ExchangeSink {
        boolean finished;

        LocalExchangeSink() {
            outstandingSinks.incrementAndGet();
        }

        @Override
        public void addPage(Page page) {
            if (allSourcesFinished == false) {
                buffer.addPage(page);
                notifyListeners();
            }
        }

        @Override
        public void finish() {
            if (finished == false) {
                finished = true;
                if (outstandingSinks.decrementAndGet() == 0) {
                    buffer.finish();
                    notifyListeners();
                }
            }
        }

        @Override
        public boolean isFinished() {
            return finished || allSourcesFinished;
        }

        @Override
        public ListenableActionFuture<Void> waitForWriting() {
            return buffer.waitForWriting();
        }
    }

    /**
     * Fetches pages and the sink status asynchronously.
     *
     * @param request  if {@link ExchangeRequest#sourcesFinished()} is true, then this handler can finish as sources have enough pages.
     * @param listener the listener that will be notified when pages are ready or this handler is finished
     * @see RemoteSink
     * @see ExchangeSourceHandler#addRemoteSink(RemoteSink, int)
     */
    public void fetchPageAsync(ExchangeRequest request, ActionListener<ExchangeResponse> listener) {
        if (request.sourcesFinished()) {
            allSourcesFinished = true;
            buffer.drainPages();
        }
        if (allSourcesFinished) {
            listener.onResponse(new ExchangeResponse(null, true));
        } else {
            listeners.add(listener);
        }
        notifyListeners();
    }

    private void notifyListeners() {
        while (listeners.isEmpty() == false && (buffer.size() > 0 || buffer.noMoreInputs())) {
            if (promised.tryAcquire() == false) {
                break;
            }
            final ActionListener<ExchangeResponse> listener;
            final ExchangeResponse response;
            try {
                // Use `poll` and recheck because `listeners.isEmpty()` might return true, while a listener is being added
                listener = listeners.poll();
                if (listener == null) {
                    continue;
                }
                response = new ExchangeResponse(buffer.pollPage(), buffer.isFinished());
            } finally {
                promised.release();
            }
            listener.onResponse(response);
        }
    }

    /**
     * Create a new exchange sink for exchanging data
     *
     * @see ExchangeSinkOperator
     */
    public ExchangeSink createExchangeSink() {
        return new LocalExchangeSink();
    }

    int bufferSize() {
        return buffer.size();
    }
}
