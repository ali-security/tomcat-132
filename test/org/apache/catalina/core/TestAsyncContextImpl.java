/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.catalina.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.LogManager;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.connector.TestCoyoteAdapter;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.catalina.valves.TesterAccessLogValve;
import org.apache.tomcat.unittest.TesterContext;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.apache.tomcat.util.collections.CaseInsensitiveKeyMap;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.easymock.EasyMock;

public class TestAsyncContextImpl extends TomcatBaseTest {

    // Time for a request to process (need to allow for threads to start etc.)
    private static final long REQUEST_TIME = 1500;
    // Timeout thread (where used) checks for timeout every second
    private static final long TIMEOUT_MARGIN = 1000;
    // Default timeout for these tests
    private static final long TIMEOUT = 5000;

    private static StringBuilder tracker;

    public static synchronized void resetTracker() {
        tracker = new StringBuilder();
    }

    public static synchronized void track(String trace) {
        tracker.append(trace);
    }

    public static synchronized String getTrack() {
        return tracker.toString();
    }

    @Test
    public void testBug49528() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        Bug49528Servlet servlet = new Bug49528Servlet();

        Wrapper wrapper = Tomcat.addServlet(ctx, "servlet", servlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "servlet");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        // Call the servlet once
        ByteChunk bc = getUrl("http://localhost:" + getPort() + "/");
        Assert.assertEquals("OK", bc.toString());

        // Give the async thread a chance to finish (but not too long)
        int counter = 0;
        while (!servlet.isDone() && counter < 10) {
            Thread.sleep(1000);
            counter++;
        }

        Assert.assertEquals("1false2true3true4true5false", servlet.getResult());

        // Check the access log
        alv.validateAccessLog(1, 200, Bug49528Servlet.THREAD_SLEEP_TIME,
                Bug49528Servlet.THREAD_SLEEP_TIME + REQUEST_TIME);
    }

    @Test
    public void testBug49567() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        Bug49567Servlet servlet = new Bug49567Servlet();

        Wrapper wrapper = Tomcat.addServlet(ctx, "servlet", servlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "servlet");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        // Call the servlet once
        ByteChunk bc = getUrl("http://localhost:" + getPort() + "/");
        Assert.assertEquals("OK", bc.toString());

        // Give the async thread a chance to finish (but not too long)
        int counter = 0;
        while (!servlet.isDone() && counter < 20) {
            Thread.sleep(1000);
            counter++;
        }

        Assert.assertEquals("1false2true3true4true5false", servlet.getResult());

        // Check the access log
        alv.validateAccessLog(1, 200, Bug49567Servlet.THREAD_SLEEP_TIME,
                Bug49567Servlet.THREAD_SLEEP_TIME + REQUEST_TIME);
    }

    @Test
    public void testAsyncStartNoComplete() throws Exception {
        resetTracker();
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // Minimise pauses during test
        Assert.assertTrue(tomcat.getConnector().setProperty("connectionTimeout", "3000"));

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        AsyncStartNoCompleteServlet servlet =
            new AsyncStartNoCompleteServlet();

        Wrapper wrapper = Tomcat.addServlet(ctx, "servlet", servlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "servlet");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        // Call the servlet the first time
        getUrl("http://localhost:" + getPort() + "/?echo=run1");
        Assert.assertEquals("OK-run1", getTrack());
        resetTracker();

        // Call the servlet the second time with a request parameter
        getUrl("http://localhost:" + getPort() + "/?echo=run2");
        Assert.assertEquals("OK-run2", getTrack());

        // Request may complete before listener has finished processing so wait
        // up to 5 seconds for the right response

        // Check the access log
        alv.validateAccessLog(2, 500,
                AsyncStartNoCompleteServlet.ASYNC_TIMEOUT,
                AsyncStartNoCompleteServlet.ASYNC_TIMEOUT + TIMEOUT_MARGIN +
                        REQUEST_TIME);
    }

    @Test
    public void testAsyncStartWithComplete() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        AsyncStartWithCompleteServlet servlet =
            new AsyncStartWithCompleteServlet();

        Wrapper wrapper = Tomcat.addServlet(ctx, "servlet", servlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "servlet");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        // Call the servlet once
        ByteChunk bc = new ByteChunk();
        Map<String,List<String>> headers = new CaseInsensitiveKeyMap<>();
        getUrl("http://localhost:" + getPort() + "/", bc, headers);

        Assert.assertEquals("OK", bc.toString());
        String contentLength = getSingleHeader("Content-Length", headers);
        Assert.assertEquals("2", contentLength);

        // Check the access log
        alv.validateAccessLog(1, 200, 0, REQUEST_TIME);
    }

    /*
     * NOTE: This servlet is only intended to be used in single-threaded tests.
     */
    private static class Bug49528Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private volatile boolean done = false;

        private StringBuilder result;

        public static final long THREAD_SLEEP_TIME = 1000;

        public String getResult() {
            return result.toString();
        }

        public boolean isDone() {
            return done;
        }

        @Override
        protected void doGet(final HttpServletRequest req,
                final HttpServletResponse resp)
                throws ServletException, IOException {

            result  = new StringBuilder();
            result.append('1');
            result.append(req.isAsyncStarted());
            req.startAsync().setTimeout(10000);
            result.append('2');
            result.append(req.isAsyncStarted());

            req.getAsyncContext().start(new Runnable() {
                @Override
                public void run() {
                    try {
                        result.append('3');
                        result.append(req.isAsyncStarted());
                        Thread.sleep(THREAD_SLEEP_TIME);
                        result.append('4');
                        result.append(req.isAsyncStarted());
                        resp.setContentType("text/plain");
                        resp.getWriter().print("OK");
                        req.getAsyncContext().complete();
                        result.append('5');
                        try {
                            // Once complete() has been called on a
                            // non-container thread it is not safe to
                            // continue to use the request object as it
                            // may be recycled at any point. Normally
                            // there is enough time for this call to
                            // complete but not always. If this call
                            // fails in Tomcat an ISE will result so
                            // handle this here with a hack. What we are
                            // really checking here is that it does not
                            // return true.
                            result.append(req.isAsyncStarted());
                        } catch (IllegalStateException npe) {
                            result.append("false");
                        } catch (Throwable t) {
                            // Additional debugging for intermittent test failure
                            result.append(t.getClass().getName());
                            t.printStackTrace();
                        }
                        done = true;
                    } catch (InterruptedException | IOException e) {
                        result.append(e);
                    }
                }
            });
            // Pointless method call so there is somewhere to put a break point
            // when debugging
            req.getMethod();
        }
    }

    /*
     * NOTE: This servlet is only intended to be used in single-threaded tests.
     */
    private static class Bug49567Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private volatile boolean done = false;

        private volatile StringBuilder result;

        public static final long THREAD_SLEEP_TIME = 1000;

        public String getResult() {
            return result.toString();
        }

        public boolean isDone() {
            return done;
        }

        @Override
        protected void doGet(final HttpServletRequest req,
                final HttpServletResponse resp)
                throws ServletException, IOException {

            result = new StringBuilder();
            result.append('1');
            result.append(req.isAsyncStarted());
            req.startAsync();
            result.append('2');
            result.append(req.isAsyncStarted());

            req.getAsyncContext().start(new Runnable() {
                @Override
                public void run() {
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                result.append('3');
                                result.append(req.isAsyncStarted());
                                Thread.sleep(THREAD_SLEEP_TIME);
                                result.append('4');
                                result.append(req.isAsyncStarted());
                                resp.setContentType("text/plain");
                                resp.getWriter().print("OK");
                                req.getAsyncContext().complete();
                                result.append('5');
                                try {
                                    // Once complete() has been called on a
                                    // non-container thread it is not safe to
                                    // continue to use the request object as it
                                    // may be recycled at any point. Normally
                                    // there is enough time for this call to
                                    // complete but not always. If this call
                                    // fails in Tomcat an NPE will result so
                                    // handle this here with a hack. What we are
                                    // really checking here is that it does not
                                    // return true.
                                    result.append(req.isAsyncStarted());
                                } catch (IllegalStateException ise) {
                                    result.append("false");
                                }
                                done = true;
                            } catch (InterruptedException | IOException e) {
                                result.append(e);
                            }
                        }
                    });
                    t.start();
                }
            });
            // Pointless method call so there is somewhere to put a break point
            // when debugging
            req.getMethod();
        }
    }

    private static class AsyncStartNoCompleteServlet extends HttpServlet {

        public static final long ASYNC_TIMEOUT = 1000;

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(final HttpServletRequest req,
                final HttpServletResponse resp)
                throws ServletException, IOException {

            String echo = req.getParameter("echo");
            AsyncContext actxt = req.startAsync();
            track("OK");
            if (echo != null) {
                track("-" + echo);
            }
            // Speed up the test by reducing the timeout
            actxt.setTimeout(ASYNC_TIMEOUT);
        }
    }

    private static class AsyncStartWithCompleteServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(final HttpServletRequest req,
                final HttpServletResponse resp)
                throws ServletException, IOException {

            AsyncContext actxt = req.startAsync();
            actxt.setTimeout(3000);
            resp.setContentType("text/plain");
            resp.getWriter().print("OK");
            actxt.complete();
        }
    }

    @Test
    public void testTimeoutListenerCompleteNoDispatch() throws Exception {
        // Should work
        doTestTimeout(Boolean.TRUE, null);
    }

    @Test
    public void testTimeoutListenerNoCompleteNoDispatch() throws Exception {
        // Should trigger an error - must do one or other
        doTestTimeout(Boolean.FALSE, null);
    }

    @Test
    public void testTimeoutListenerCompleteNonAsyncDispatch() throws Exception {
        // Should trigger an error - can't do both
        doTestTimeout(Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    public void testTimeoutListenerNoCompleteNonAsyncDispatch()
            throws Exception {
        // Should work
        doTestTimeout(Boolean.FALSE, Boolean.FALSE);
    }

    @Test
    public void testTimeoutListenerCompleteAsyncDispatch() throws Exception {
        // Should trigger an error - can't do both
        doTestTimeout(Boolean.TRUE, Boolean.TRUE);
    }

    @Test
    public void testTimeoutListenerNoCompleteAsyncDispatch() throws Exception {
        // Should work
        doTestTimeout(Boolean.FALSE, Boolean.TRUE);
    }

    @Test
    public void testTimeoutNoListener() throws Exception {
        // Should work
        doTestTimeout(null, null);
    }

    private void doTestTimeout(Boolean completeOnTimeout, Boolean asyncDispatch)
            throws Exception {

        resetTracker();

        String dispatchUrl = null;
        if (asyncDispatch != null) {
            if (asyncDispatch.booleanValue()) {
                dispatchUrl = "/async";
            } else {
                dispatchUrl = "/nonasync";
            }
        }

        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        TimeoutServlet timeout = new TimeoutServlet(completeOnTimeout, dispatchUrl);

        Wrapper wrapper = Tomcat.addServlet(ctx, "time", timeout);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/start", "time");

        if (asyncDispatch != null) {
            if (asyncDispatch.booleanValue()) {
                AsyncStartRunnable asyncStartRunnable =
                        new AsyncStartRunnable();
                Wrapper async =
                        Tomcat.addServlet(ctx, "async", asyncStartRunnable);
                async.setAsyncSupported(true);
                ctx.addServletMappingDecoded(dispatchUrl, "async");
            } else {
                NonAsyncServlet nonAsync = new NonAsyncServlet();
                Tomcat.addServlet(ctx, "nonasync", nonAsync);
                ctx.addServletMappingDecoded(dispatchUrl, "nonasync");
            }
         }

        ctx.addApplicationListener(TrackingRequestListener.class.getName());

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);
        TesterAccessLogValve alvGlobal = new TesterAccessLogValve();
        tomcat.getHost().getPipeline().addValve(alvGlobal);

        tomcat.start();
        try {
            getUrl("http://localhost:" + getPort() + "/start");
        } catch (IOException ioe) {
            // Ignore - expected for some error conditions
        }
        StringBuilder expected = new StringBuilder("requestInitialized-");
        expected.append("TimeoutServletGet-");
        if (completeOnTimeout == null) {
            expected.append("requestDestroyed");
        } else if (completeOnTimeout.booleanValue()) {
            expected.append("onTimeout-");
            expected.append("onComplete-");
            expected.append("requestDestroyed");
        } else {
            expected.append("onTimeout-");
            if (asyncDispatch != null) {
                if (asyncDispatch.booleanValue()) {
                    expected.append("onStartAsync-Runnable-");
                } else {
                    expected.append("NonAsyncServletGet-");
                }
            }
            expected.append("onComplete-");
            expected.append("requestDestroyed");
        }
        // Request may complete before listener has finished processing so wait
        // up to 5 seconds for the right response
        String expectedTrack = expected.toString();
        int count = 0;
        while (!expectedTrack.equals(getTrack()) && count < 100) {
            Thread.sleep(50);
            count ++;
        }
        Assert.assertEquals(expectedTrack, getTrack());

        // Check the access log
        if (completeOnTimeout == null ||
                (!completeOnTimeout.booleanValue() && asyncDispatch == null)) {
            alvGlobal.validateAccessLog(1, 500, TimeoutServlet.ASYNC_TIMEOUT,
                    TimeoutServlet.ASYNC_TIMEOUT + TIMEOUT_MARGIN +
                    REQUEST_TIME);
            alv.validateAccessLog(1, 500, TimeoutServlet.ASYNC_TIMEOUT,
                    TimeoutServlet.ASYNC_TIMEOUT + TIMEOUT_MARGIN +
                    REQUEST_TIME);
        } else {
            long timeoutDelay = TimeoutServlet.ASYNC_TIMEOUT;
            if (asyncDispatch != null && asyncDispatch.booleanValue() &&
                    !completeOnTimeout.booleanValue()) {
                // The async dispatch includes a sleep
                timeoutDelay += AsyncStartRunnable.THREAD_SLEEP_TIME;
            }
            alvGlobal.validateAccessLog(1, 200, timeoutDelay,
                    timeoutDelay + TIMEOUT_MARGIN + REQUEST_TIME);
            alv.validateAccessLog(1, 200, timeoutDelay,
                    timeoutDelay + TIMEOUT_MARGIN + REQUEST_TIME);
        }

        Assert.assertTrue(timeout.isAsyncStartedCorrect());
    }

    private static class TimeoutServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        private final Boolean completeOnTimeout;
        private final transient TrackingListener trackingListener;

        public static final long ASYNC_TIMEOUT = 100;

        TimeoutServlet(Boolean completeOnTimeout, String dispatchUrl) {
            this.completeOnTimeout = completeOnTimeout;
            if (completeOnTimeout == null) {
                this.trackingListener = null;
            } else {
                this.trackingListener = new TrackingListener(false, completeOnTimeout.booleanValue(), dispatchUrl);
            }
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            if (req.isAsyncSupported()) {
                track("TimeoutServletGet-");
                final AsyncContext ac = req.startAsync();
                ac.setTimeout(ASYNC_TIMEOUT);

                if (completeOnTimeout != null) {
                    ac.addListener(trackingListener);
                }
            } else {
                resp.getWriter().print("FAIL: Async unsupported");
            }
        }

        public boolean isAsyncStartedCorrect() {
            if (trackingListener == null) {
                return true;
            }
            return trackingListener.isAsyncStartedCorrect();
        }
    }

    @Test
    public void testDispatchSingle() throws Exception {
        doTestDispatch(1, false);
    }

    @Test
    public void testDispatchDouble() throws Exception {
        doTestDispatch(2, false);
    }

    @Test
    public void testDispatchMultiple() throws Exception {
        doTestDispatch(5, false);
    }

    @Test
    public void testDispatchWithThreadSingle() throws Exception {
        doTestDispatch(1, true);
    }

    @Test
    public void testDispatchWithThreadDouble() throws Exception {
        doTestDispatch(2, true);
    }

    @Test
    public void testDispatchWithThreadMultiple() throws Exception {
        doTestDispatch(5, true);
    }

    private void doTestDispatch(int iter, boolean useThread) throws Exception {
        resetTracker();
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        DispatchingServlet dispatch = new DispatchingServlet(false, false);
        Wrapper wrapper = Tomcat.addServlet(ctx, "dispatch", dispatch);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/stage1", "dispatch");

        NonAsyncServlet nonasync = new NonAsyncServlet();
        Wrapper wrapper2 = Tomcat.addServlet(ctx, "nonasync", nonasync);
        wrapper2.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/stage2", "nonasync");

        ctx.addApplicationListener(TrackingRequestListener.class.getName());

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        StringBuilder url = new StringBuilder(48);
        url.append("http://localhost:");
        url.append(getPort());
        url.append("/stage1?iter=");
        url.append(iter);
        if (useThread) {
            url.append("&useThread=y");
        }
        getUrl(url.toString());

        StringBuilder expected = new StringBuilder("requestInitialized-");
        int loop = iter;
        while (loop > 0) {
            expected.append("DispatchingServletGet-");
            loop--;
        }
        expected.append("NonAsyncServletGet-");
        expected.append("requestDestroyed");
        // Request may complete before listener has finished processing so wait
        // up to 5 seconds for the right response
        String expectedTrack = expected.toString();
        int count = 0;
        while (!expectedTrack.equals(getTrack()) && count < 100) {
            Thread.sleep(50);
            count ++;
        }
        Assert.assertEquals(expectedTrack, getTrack());
        Assert.assertTrue(dispatch.isAsyncStartedCorrect());

        // Check the access log
        alv.validateAccessLog(1, 200, 0, REQUEST_TIME);
    }

    private static class DispatchingServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;
        private static final String ITER_PARAM = "iter";
        private static final String DISPATCH_CHECK = "check";
        private final transient TrackingListener trackingListener;

        DispatchingServlet(boolean addTrackingListener,
                boolean completeOnError) {
            if (addTrackingListener) {
                trackingListener = new TrackingListener(completeOnError, true, null);
            } else {
                trackingListener = null;
            }
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            if ("y".equals(req.getParameter(DISPATCH_CHECK))) {
                if (req.getDispatcherType() != DispatcherType.ASYNC) {
                    track("WrongDispatcherType-");
                }
            }
            track("DispatchingServletGet-");
            final int iter = Integer.parseInt(req.getParameter(ITER_PARAM)) - 1;
            final AsyncContext ctxt = req.startAsync();
            if (trackingListener != null) {
                ctxt.addListener(trackingListener);
            }
            Runnable run = new Runnable() {
                @Override
                public void run() {
                    if (iter > 0) {
                        ctxt.dispatch("/stage1?" + ITER_PARAM + "=" + iter +
                                "&" + DISPATCH_CHECK + "=y");
                    } else {
                        ctxt.dispatch("/stage2");
                    }
                }
            };
            if ("y".equals(req.getParameter("useThread"))) {
                new Thread(run).start();
            } else {
                run.run();
            }
        }

        public boolean isAsyncStartedCorrect() {
            if (trackingListener == null) {
                return true;
            }
            return trackingListener.isAsyncStartedCorrect();
        }
    }

    private static class NonAsyncServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            track("NonAsyncServletGet-");
        }
    }

    @Test
    public void testListeners() throws Exception {
        resetTracker();
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        TrackingServlet tracking = new TrackingServlet();
        Wrapper wrapper = Tomcat.addServlet(ctx, "tracking", tracking);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/stage1", "tracking");

        TimeoutServlet timeout = new TimeoutServlet(Boolean.TRUE, null);
        Wrapper wrapper2 = Tomcat.addServlet(ctx, "timeout", timeout);
        wrapper2.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/stage2", "timeout");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        StringBuilder url = new StringBuilder(48);
        url.append("http://localhost:");
        url.append(getPort());
        url.append("/stage1");

        getUrl(url.toString());

        // Request may complete before listener has finished processing so wait
        // up to 5 seconds for the right response
        String expectedTrack = "DispatchingServletGet-DispatchingServletGet-" +
                "onStartAsync-TimeoutServletGet-onStartAsync-onTimeout-" +
                "onComplete-";
        int count = 0;
        while (!expectedTrack.equals(getTrack()) && count < 100) {
            Thread.sleep(50);
            count ++;
        }
        Assert.assertEquals(expectedTrack, getTrack());

        // Check the access log
        alv.validateAccessLog(1, 200, TimeoutServlet.ASYNC_TIMEOUT,
                TimeoutServlet.ASYNC_TIMEOUT + TIMEOUT_MARGIN + REQUEST_TIME);
    }

    private static class TrackingServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private static volatile boolean first = true;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            track("DispatchingServletGet-");
            resp.flushBuffer();

            final boolean first = TrackingServlet.first;
            TrackingServlet.first = false;

            final AsyncContext ctxt = req.startAsync();
            TrackingListener listener = new TrackingListener(false, true, null);
            ctxt.addListener(listener);
            ctxt.setTimeout(3000);

            Runnable run = new Runnable() {
                @Override
                public void run() {
                    if (first) {
                        ctxt.dispatch("/stage1");
                    } else {
                        ctxt.dispatch("/stage2");
                    }
                }
            };
            if ("y".equals(req.getParameter("useThread"))) {
                new Thread(run).start();
            } else {
                run.run();
            }
        }
    }

    public static class TrackingListener implements AsyncListener {

        private final boolean completeOnError;
        private final boolean completeOnTimeout;
        private final String dispatchUrl;
        // Assumes listener is fired after container thread that initiated async
        // has exited.
        private boolean asyncStartedCorrect = true;

        public TrackingListener(boolean completeOnError,
                boolean completeOnTimeout, String dispatchUrl) {
            this.completeOnError = completeOnError;
            this.completeOnTimeout = completeOnTimeout;
            this.dispatchUrl = dispatchUrl;
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException {
            track("onComplete-");
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            boolean expectedAsyncStarted = true;

            track("onTimeout-");
            if (completeOnTimeout){
                event.getAsyncContext().complete();
                expectedAsyncStarted = false;
            }
            if (dispatchUrl != null) {
                event.getAsyncContext().dispatch(dispatchUrl);
                expectedAsyncStarted = false;
            }

            ServletRequest req = event.getSuppliedRequest();
            asyncStartedCorrect = (expectedAsyncStarted == req.isAsyncStarted());
        }

        @Override
        public void onError(AsyncEvent event) throws IOException {
            boolean expectedAsyncStarted = true;

            track("onError-");
            if (completeOnError) {
                event.getAsyncContext().complete();
                expectedAsyncStarted = false;
            }

            ServletRequest req = event.getSuppliedRequest();
            asyncStartedCorrect = (expectedAsyncStarted == req.isAsyncStarted());
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException {
            track("onStartAsync-");
        }

        public boolean isAsyncStartedCorrect() {
            return asyncStartedCorrect;
        }
    }

    private static class StickyTrackingListener extends TrackingListener {

        StickyTrackingListener(boolean completeOnError,
                boolean completeOnTimeout, String dispatchUrl) {
            super(completeOnError, completeOnTimeout, dispatchUrl);
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException {
            super.onStartAsync(event);
            // Re-add this listener to the new AsyncContext
            event.getAsyncContext().addListener(this);
        }
    }

    public static class TrackingRequestListener
            implements ServletRequestListener {

        @Override
        public void requestDestroyed(ServletRequestEvent sre) {
            track("requestDestroyed");
        }

        @Override
        public void requestInitialized(ServletRequestEvent sre) {
            track("requestInitialized-");
        }
    }

    @Test
    public void testDispatchErrorSingle() throws Exception {
        doTestDispatchError(1, false, false);
    }

    @Test
    public void testDispatchErrorDouble() throws Exception {
        doTestDispatchError(2, false, false);
    }

    @Test
    public void testDispatchErrorMultiple() throws Exception {
        doTestDispatchError(5, false, false);
    }

    @Test
    public void testDispatchErrorWithThreadSingle() throws Exception {
        doTestDispatchError(1, true, false);
    }

    @Test
    public void testDispatchErrorWithThreadDouble() throws Exception {
        doTestDispatchError(2, true, false);
    }

    @Test
    public void testDispatchErrorWithThreadMultiple() throws Exception {
        doTestDispatchError(5, true, false);
    }

    @Test
    public void testDispatchErrorSingleThenComplete() throws Exception {
        doTestDispatchError(1, false, true);
    }

    @Test
    public void testDispatchErrorDoubleThenComplete() throws Exception {
        doTestDispatchError(2, false, true);
    }

    @Test
    public void testDispatchErrorMultipleThenComplete() throws Exception {
        doTestDispatchError(5, false, true);
    }

    @Test
    public void testDispatchErrorWithThreadSingleThenComplete()
            throws Exception {
        doTestDispatchError(1, true, true);
    }

    @Test
    public void testDispatchErrorWithThreadDoubleThenComplete()
            throws Exception {
        doTestDispatchError(2, true, true);
    }

    @Test
    public void testDispatchErrorWithThreadMultipleThenComplete()
            throws Exception {
        doTestDispatchError(5, true, true);
    }

    private void doTestDispatchError(int iter, boolean useThread,
            boolean completeOnError)
            throws Exception {
        resetTracker();
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        DispatchingServlet dispatch =
            new DispatchingServlet(true, completeOnError);
        Wrapper wrapper = Tomcat.addServlet(ctx, "dispatch", dispatch);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/stage1", "dispatch");

        ErrorServlet error = new ErrorServlet();
        Tomcat.addServlet(ctx, "error", error);
        ctx.addServletMappingDecoded("/stage2", "error");

        ctx.addApplicationListener(TrackingRequestListener.class.getName());

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        StringBuilder url = new StringBuilder(48);
        url.append("http://localhost:");
        url.append(getPort());
        url.append("/stage1?iter=");
        url.append(iter);
        if (useThread) {
            url.append("&useThread=y");
        }
        getUrl(url.toString());

        StringBuilder expected = new StringBuilder("requestInitialized-");
        int loop = iter;
        while (loop > 0) {
            expected.append("DispatchingServletGet-");
            if (loop != iter) {
                expected.append("onStartAsync-");
            }
            loop--;
        }
        expected.append("ErrorServletGet-onError-onComplete-requestDestroyed");
        // Request may complete before listener has finished processing so wait
        // up to 5 seconds for the right response
        String expectedTrack = expected.toString();
        int count = 0;
        while (!expectedTrack.equals(getTrack()) && count < 100) {
            Thread.sleep(50);
            count ++;
        }
        Assert.assertEquals(expectedTrack, getTrack());
        Assert.assertTrue(dispatch.isAsyncStartedCorrect());

        // Check the access log
        alv.validateAccessLog(1, 500, 0, REQUEST_TIME);
    }

    private static class ErrorServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            track("ErrorServletGet-");
            try {
                // Give the original thread a chance to exit the
                // ErrorReportValve before we throw this exception
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new ServletException(e);
            }
            throw new ServletException("Oops.");
        }
    }

    @Test
    public void testBug50352() throws Exception {
        resetTracker();
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        AsyncStartRunnable servlet = new AsyncStartRunnable();
        Wrapper wrapper = Tomcat.addServlet(ctx, "servlet", servlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "servlet");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        getUrl("http://localhost:" + getPort() + "/");

        // Request may complete before listener has finished processing so wait
        // up to 5 seconds for the right response
        String expectedTrack = "Runnable-onComplete-";
        int count = 0;
        while (!expectedTrack.equals(getTrack()) && count < 100) {
            Thread.sleep(50);
            count ++;
        }
        Assert.assertEquals(expectedTrack, getTrack());

        // Check the access log
        alv.validateAccessLog(1, 200, AsyncStartRunnable.THREAD_SLEEP_TIME,
                AsyncStartRunnable.THREAD_SLEEP_TIME + REQUEST_TIME);
    }

    private static final class AsyncStartRunnable extends HttpServlet {

        private static final long serialVersionUID = 1L;

        public static final long THREAD_SLEEP_TIME = 3000;

        @Override
        protected void doGet(HttpServletRequest request,
                HttpServletResponse response)
                throws ServletException, IOException {

            final AsyncContext asyncContext =
                request.startAsync(request, response);

            asyncContext.addListener(new TrackingListener(false, false, null));

            asyncContext.start(new Runnable() {

                @Override
                public void run() {
                    try {
                        Thread.sleep(THREAD_SLEEP_TIME);
                        track("Runnable-");
                        asyncContext.complete();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    @Test
    public void testBug50753() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        Bug50753Servlet servlet = new Bug50753Servlet();

        Wrapper wrapper = Tomcat.addServlet(ctx, "servlet", servlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "servlet");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        // Call the servlet once
        Map<String,List<String>> headers = new CaseInsensitiveKeyMap<>();
        ByteChunk bc = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + "/", bc, headers);
        Assert.assertEquals(200, rc);
        Assert.assertEquals("OK", bc.toString());
        String testHeader = getSingleHeader("A", headers);
        Assert.assertEquals("xyz",testHeader);

        // Check the access log
        alv.validateAccessLog(1, 200, Bug50753Servlet.THREAD_SLEEP_TIME,
                Bug50753Servlet.THREAD_SLEEP_TIME + REQUEST_TIME);
    }

    private static class Bug50753Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        public static final long THREAD_SLEEP_TIME = 5000;

        @Override
        protected void doGet(HttpServletRequest req,
                final HttpServletResponse resp)
                throws ServletException, IOException {
            final AsyncContext ctx = req.startAsync();
            ctx.start(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(THREAD_SLEEP_TIME);
                        resp.setHeader("A", "xyz");
                        resp.setContentType("text/plain");
                        resp.setContentLength("OK".getBytes().length);
                        resp.getWriter().print("OK");
                        ctx.complete();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    @Test
    public void testErrorHandling() throws Exception {
        resetTracker();
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        ErrorServlet error = new ErrorServlet();
        Tomcat.addServlet(ctx, "error", error);
        ctx.addServletMappingDecoded("/error", "error");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        StringBuilder url = new StringBuilder(48);
        url.append("http://localhost:");
        url.append(getPort());
        url.append("/error");

        int rc = getUrl(url.toString(), new ByteChunk(), null);

        Assert.assertEquals(500, rc);

        // Without this test may complete before access log has a chance to log
        // the request
        Thread.sleep(REQUEST_TIME);

        // Check the access log
        alv.validateAccessLog(1, 500, 0, REQUEST_TIME);
    }

    @Test
    public void testCommitOnComplete() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        AsyncStatusServlet asyncStatusServlet =
            new AsyncStatusServlet(HttpServletResponse.SC_BAD_REQUEST);
        Wrapper wrapper =
            Tomcat.addServlet(ctx, "asyncStatusServlet", asyncStatusServlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/asyncStatusServlet", "asyncStatusServlet");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        StringBuilder url = new StringBuilder(48);
        url.append("http://localhost:");
        url.append(getPort());
        url.append("/asyncStatusServlet");

        int rc = getUrl(url.toString(), new ByteChunk(), null);

        Assert.assertEquals(HttpServletResponse.SC_BAD_REQUEST, rc);

        // Without this test may complete before access log has a chance to log
        // the request
        Thread.sleep(REQUEST_TIME);

        // Check the access log
        alv.validateAccessLog(1, HttpServletResponse.SC_BAD_REQUEST, 0,
                REQUEST_TIME);

    }

    private static class AsyncStatusServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private int status;

        AsyncStatusServlet(int status) {
            this.status = status;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            AsyncContext actxt = req.startAsync();
            resp.setStatus(status);
            actxt.complete();
        }
    }

    @Test
    public void testBug51197a() throws Exception {
        doTestBug51197(false, false);
    }

    @Test
    public void testBug51197b() throws Exception {
        doTestBug51197(true, false);
    }

    @Test
    public void testBug51197c() throws Exception {
        doTestBug51197(false, true);
    }

    @Test
    public void testBug51197d() throws Exception {
        doTestBug51197(true, true);
    }

    private void doTestBug51197(boolean threaded, boolean customError) throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        AsyncErrorServlet asyncErrorServlet =
            new AsyncErrorServlet(HttpServletResponse.SC_BAD_REQUEST, threaded);
        Wrapper wrapper =
            Tomcat.addServlet(ctx, "asyncErrorServlet", asyncErrorServlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/asyncErrorServlet", "asyncErrorServlet");

        if (customError) {
            CustomErrorServlet customErrorServlet = new CustomErrorServlet();
            Tomcat.addServlet(ctx, "customErrorServlet", customErrorServlet);
            ctx.addServletMappingDecoded("/customErrorServlet", "customErrorServlet");

            ErrorPage ep = new ErrorPage();
            ep.setLocation("/customErrorServlet");

            ctx.addErrorPage(ep);
        }

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        StringBuilder url = new StringBuilder(48);
        url.append("http://localhost:");
        url.append(getPort());
        url.append("/asyncErrorServlet");

        ByteChunk res = new ByteChunk();
        int rc = getUrl(url.toString(), res, null);

        Assert.assertEquals(HttpServletResponse.SC_BAD_REQUEST, rc);

        // SRV 10.9.2 - Handling an error is entirely the application's
        // responsibility when an error occurs on an application thread.
        // Calling sendError() followed by complete() and expecting the standard
        // error page mechanism to kick in could be viewed as handling the error
        String responseBody = res.toString();
        Assert.assertNotNull(responseBody);
        Assert.assertTrue(responseBody.length() > 0);
        if (customError) {
            Assert.assertTrue(responseBody, responseBody.contains(CustomErrorServlet.ERROR_MESSAGE));
        } else {
            Assert.assertTrue(responseBody, responseBody.contains(AsyncErrorServlet.ERROR_MESSAGE));
        }

        // Without this test may complete before access log has a chance to log
        // the request
        Thread.sleep(REQUEST_TIME);

        // Check the access log
        alv.validateAccessLog(1, HttpServletResponse.SC_BAD_REQUEST, 0,
                REQUEST_TIME);
    }

    private static class CustomErrorServlet extends GenericServlet {

        private static final long serialVersionUID = 1L;

        public static final String ERROR_MESSAGE = "Custom error page";

        @Override
        public void service(ServletRequest req, ServletResponse res)
                throws ServletException, IOException {
            res.getWriter().print(ERROR_MESSAGE);
        }

    }


    private static class AsyncErrorServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        public static final String ERROR_MESSAGE = "It broke.";

        private int status;
        private boolean threaded;

        AsyncErrorServlet(int status, boolean threaded) {
            this.status = status;
            this.threaded = threaded;
        }

        @Override
        protected void doGet(HttpServletRequest req, final HttpServletResponse resp)
                throws ServletException, IOException {

            final AsyncContext actxt = req.startAsync();
            actxt.setTimeout(TIMEOUT);
            if (threaded) {
                actxt.start(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            resp.sendError(status, ERROR_MESSAGE);
                            actxt.complete();
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                });
            } else {
                resp.sendError(status, ERROR_MESSAGE);
                actxt.complete();
            }
        }
    }

    @Test
    public void testBug53337() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        Wrapper a = Tomcat.addServlet(ctx, "ServletA", new Bug53337ServletA());
        a.setAsyncSupported(true);
        Wrapper b = Tomcat.addServlet(ctx, "ServletB", new Bug53337ServletB());
        b.setAsyncSupported(true);
        Tomcat.addServlet(ctx, "ServletC", new Bug53337ServletC());
        ctx.addServletMappingDecoded("/ServletA", "ServletA");
        ctx.addServletMappingDecoded("/ServletB", "ServletB");
        ctx.addServletMappingDecoded("/ServletC", "ServletC");

        tomcat.start();

        StringBuilder url = new StringBuilder(48);
        url.append("http://localhost:");
        url.append(getPort());
        url.append("/ServletA");

        ByteChunk body = new ByteChunk();
        int rc = getUrl(url.toString(), body, null);

        Assert.assertEquals(HttpServletResponse.SC_OK, rc);
        Assert.assertEquals("OK", body.toString());
    }

    private static class Bug53337ServletA extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            RequestDispatcher rd = req.getRequestDispatcher("/ServletB");
            rd.forward(req, resp);
        }
    }

    private static class Bug53337ServletB extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(final HttpServletRequest req,
                final HttpServletResponse resp)
                throws ServletException, IOException {

            final AsyncContext async = req.startAsync();
            // Just for debugging
            async.setTimeout(100000);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(new Runnable() {

                @Override
                public void run() {
                    async.dispatch("/ServletC");
                }
            });
            executor.shutdown();
        }
    }

    private static class Bug53337ServletC extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.getWriter().print("OK");
        }
    }

    @Test
    public void testBug53843() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        Bug53843ServletA servletA = new Bug53843ServletA();
        Wrapper a = Tomcat.addServlet(ctx, "ServletA", servletA);
        a.setAsyncSupported(true);
        Tomcat.addServlet(ctx, "ServletB", new Bug53843ServletB());

        ctx.addServletMappingDecoded("/ServletA", "ServletA");
        ctx.addServletMappingDecoded("/ServletB", "ServletB");

        tomcat.start();

        StringBuilder url = new StringBuilder(48);
        url.append("http://localhost:");
        url.append(getPort());
        url.append("/ServletA");

        ByteChunk body = new ByteChunk();
        int rc = getUrl(url.toString(), body, null);

        Assert.assertEquals(HttpServletResponse.SC_OK, rc);
        Assert.assertEquals("OK", body.toString());
        Assert.assertTrue(servletA.isAsyncWhenExpected());
    }

    private static class Bug53843ServletA extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private boolean isAsyncWhenExpected = true;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            // Should not be async at this point
            isAsyncWhenExpected = isAsyncWhenExpected && !req.isAsyncStarted();

            final AsyncContext async = req.startAsync();

            // Should be async at this point
            isAsyncWhenExpected = isAsyncWhenExpected && req.isAsyncStarted();

            async.start(new Runnable() {

                @Override
                public void run() {
                    // This should be delayed until the original container
                    // thread exists
                    async.dispatch("/ServletB");
                }
            });

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new ServletException(e);
            }

            // Should be async at this point
            isAsyncWhenExpected = isAsyncWhenExpected && req.isAsyncStarted();
        }

        public boolean isAsyncWhenExpected() {
            return isAsyncWhenExpected;
        }
    }

    private static class Bug53843ServletB extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.getWriter().print("OK");
        }
    }

    @Test
    public void testTimeoutErrorDispatchNone() throws Exception {
        doTestTimeoutErrorDispatch(null, null);
    }

    @Test
    public void testTimeoutErrorDispatchNonAsync() throws Exception {
        doTestTimeoutErrorDispatch(Boolean.FALSE, null);
    }

    @Test
    public void testTimeoutErrorDispatchAsyncStart() throws Exception {
        doTestTimeoutErrorDispatch(
                Boolean.TRUE, ErrorPageAsyncMode.NO_COMPLETE);
    }

    @Test
    public void testTimeoutErrorDispatchAsyncComplete() throws Exception {
        doTestTimeoutErrorDispatch(Boolean.TRUE, ErrorPageAsyncMode.COMPLETE);
    }

    @Test
    public void testTimeoutErrorDispatchAsyncDispatch() throws Exception {
        doTestTimeoutErrorDispatch(Boolean.TRUE, ErrorPageAsyncMode.DISPATCH);
    }

    private void doTestTimeoutErrorDispatch(Boolean asyncError,
            ErrorPageAsyncMode mode) throws Exception {
        resetTracker();
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        TimeoutServlet timeout = new TimeoutServlet(null, null);
        Wrapper w1 = Tomcat.addServlet(ctx, "time", timeout);
        w1.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/async", "time");

        NonAsyncServlet nonAsync = new NonAsyncServlet();
        Wrapper w2 = Tomcat.addServlet(ctx, "nonAsync", nonAsync);
        w2.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/error/nonasync", "nonAsync");

        AsyncErrorPage asyncErrorPage = new AsyncErrorPage(mode);
        Wrapper w3 = Tomcat.addServlet(ctx, "asyncErrorPage", asyncErrorPage);
        w3.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/error/async", "asyncErrorPage");

        if (asyncError != null) {
            ErrorPage ep = new ErrorPage();
            ep.setErrorCode(500);
            if (asyncError.booleanValue()) {
                ep.setLocation("/error/async");
            } else {
                ep.setLocation("/error/nonasync");
            }

            ctx.addErrorPage(ep);
        }

        ctx.addApplicationListener(TrackingRequestListener.class.getName());

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);
        TesterAccessLogValve alvGlobal = new TesterAccessLogValve();
        tomcat.getHost().getPipeline().addValve(alvGlobal);

        tomcat.start();
        ByteChunk res = new ByteChunk();
        try {
            getUrl("http://localhost:" + getPort() + "/async", res, null);
        } catch (IOException ioe) {
            // Ignore - expected for some error conditions
        }

        StringBuilder expected = new StringBuilder();
        expected.append("requestInitialized-TimeoutServletGet-");
        if (asyncError != null) {
            if (asyncError.booleanValue()) {
                expected.append("AsyncErrorPageGet-");
                if (mode == ErrorPageAsyncMode.NO_COMPLETE){
                    expected.append("NoOp-");
                } else if (mode == ErrorPageAsyncMode.COMPLETE) {
                    expected.append("Complete-");
                } else if (mode == ErrorPageAsyncMode.DISPATCH) {
                    expected.append("Dispatch-NonAsyncServletGet-");
                }
            } else {
                expected.append("NonAsyncServletGet-");
            }
        }
        expected.append("requestDestroyed");

        // Request may complete before listener has finished processing so wait
        // up to 5 seconds for the right response
        String expectedTrack = expected.toString();
        int count = 0;
        while (!expectedTrack.equals(getTrack()) && count < 100) {
            Thread.sleep(50);
            count ++;
        }
        Assert.assertEquals(expectedTrack, getTrack());

        // Check the access log
        alvGlobal.validateAccessLog(1, 500, TimeoutServlet.ASYNC_TIMEOUT,
                TimeoutServlet.ASYNC_TIMEOUT + TIMEOUT_MARGIN +
                REQUEST_TIME);
        alv.validateAccessLog(1, 500, TimeoutServlet.ASYNC_TIMEOUT,
                TimeoutServlet.ASYNC_TIMEOUT + TIMEOUT_MARGIN +
                REQUEST_TIME);
    }

    private enum ErrorPageAsyncMode {
        NO_COMPLETE,
        COMPLETE,
        DISPATCH
    }

    private static class AsyncErrorPage extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private final ErrorPageAsyncMode mode;

        AsyncErrorPage(ErrorPageAsyncMode mode) {
            this.mode = mode;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            track("AsyncErrorPageGet-");

            final AsyncContext ctxt = req.getAsyncContext();

            switch(mode) {
                case COMPLETE:
                    track("Complete-");
                    ctxt.complete();
                    break;
                case DISPATCH:
                    track("Dispatch-");
                    ctxt.dispatch("/error/nonasync");
                    break;
                case NO_COMPLETE:
                    track("NoOp-");
                    break;
                default:
                    // Impossible
                    break;
            }
        }
    }

    @Test
    public void testBug54178() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        Bug54178ServletA bug54178ServletA = new Bug54178ServletA();
        Wrapper wrapper =
            Tomcat.addServlet(ctx, "bug54178ServletA", bug54178ServletA);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/bug54178ServletA", "bug54178ServletA");

        Bug54178ServletB bug54178ServletB = new Bug54178ServletB();
        Tomcat.addServlet(ctx, "bug54178ServletB", bug54178ServletB);
        ctx.addServletMappingDecoded("/bug54178ServletB", "bug54178ServletB");

        tomcat.start();

        ByteChunk body = new ByteChunk();
        int rc = -1;

        try {
            rc = getUrl("http://localhost:" + getPort() + "/bug54178ServletA?" +
                    Bug54178ServletA.PARAM_NAME + "=bar",
                    body, null);
        } catch (IOException ioe) {
            // This may happen if test fails. Output the exception in case it is
            // useful and let asserts handle the failure
            ioe.printStackTrace();
        }

        Assert.assertEquals(HttpServletResponse.SC_OK, rc);

        body.recycle();

        rc = getUrl("http://localhost:" + getPort() + "/bug54178ServletB",
                body, null);

        Assert.assertEquals(HttpServletResponse.SC_OK, rc);
        Assert.assertEquals("OK", body.toString());
    }

    private static class Bug54178ServletA extends HttpServlet {

        public static final String PARAM_NAME = "foo";
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            req.getParameter(PARAM_NAME);
            AsyncContext actxt = req.startAsync();
            actxt.addListener(new Bug54178AsyncListener());
            actxt.complete();
        }
    }

    private static class Bug54178ServletB extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            resp.setContentType("text/plain");
            PrintWriter pw = resp.getWriter();
            String result = req.getParameter(Bug54178ServletA.PARAM_NAME);
            if (result == null) {
                pw.write("OK");
            } else {
                pw.write("FAIL");
            }
        }
    }

    private static class Bug54178AsyncListener implements AsyncListener {

        @Override
        public void onComplete(AsyncEvent event) throws IOException {
            throw new RuntimeException("Testing Bug54178");
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            // NO-OP
        }

        @Override
        public void onError(AsyncEvent event) throws IOException {
            // NO-OP
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException {
            // NO-OP
        }
    }


    @Test
    public void testBug59219a() throws Exception{
        doTestBug59219("", "doGet-onError-onComplete-");
    }


    @Test
    public void testBug59219b() throws Exception{
        doTestBug59219("?loops=3", "doGet-doGet-onStartAsync-doGet-onStartAsync-onError-onComplete-");
    }


    private void doTestBug59219(String queryString, String expectedTrack) throws Exception {
        resetTracker();
        Tomcat tomcat = getTomcatInstance();

        Context ctx = tomcat.addContext("", null);
        Bug59219Servlet bug59219Servlet = new Bug59219Servlet();
        Wrapper w = tomcat.addServlet("", "async", bug59219Servlet);
        w.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/async", "async");

        tomcat.start();

        getUrl("http://localhost:" + getPort() + "/async" + queryString);

        // Wait up to 5s for the response
        int count = 0;
        while(!expectedTrack.equals(getTrack()) && count < 100) {
            Thread.sleep(50);
            count++;
        }

        Assert.assertEquals(expectedTrack, getTrack());
    }


    private static class Bug59219Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private final transient TrackingListener trackingListener = new TrackingListener(true, false, "/async");
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            track("doGet-");
            AsyncContext ctx = req.startAsync();
            ctx.setTimeout(3000);
            ctx.addListener(trackingListener);

            String loopsParam = req.getParameter("loops");
            Integer loopsAttr = (Integer) req.getAttribute("loops");

            int loops = 0;
            if (loopsAttr != null) {
                loops = loopsAttr.intValue();
            } else if (loopsParam != null) {
                loops = Integer.parseInt(loopsParam);
            }

            if (loops > 1) {
                loops--;
                req.setAttribute("loops", Integer.valueOf(loops));
                ctx.dispatch();
            } else {
              throw new ServletException();
            }
        }
    }

    @Test
    public void testForbiddenDispatching() throws Exception {
        resetTracker();
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        NonAsyncServlet nonAsyncServlet = new NonAsyncServlet();
        Wrapper wrapper = Tomcat.addServlet(ctx, "nonAsyncServlet",
                nonAsyncServlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/target", "nonAsyncServlet");

        DispatchingGenericServlet forbiddenDispatchingServlet = new DispatchingGenericServlet();
        Wrapper wrapper1 = Tomcat.addServlet(ctx,
                "forbiddenDispatchingServlet", forbiddenDispatchingServlet);
        wrapper1.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/forbiddenDispatchingServlet",
                "forbiddenDispatchingServlet");

        tomcat.start();

        try {
            getUrl("http://localhost:" + getPort()
                    + "/forbiddenDispatchingServlet");
        } catch (IOException ioe) {
            // This may happen if test fails. Output the exception in case it is
            // useful and let asserts handle the failure
            ioe.printStackTrace();
        }

        // Request may complete before listener has finished processing so wait
        // up to 5 seconds for the right response
        String expectedTrack = "OKNonAsyncServletGet-";
        int count = 0;
        while (!expectedTrack.equals(getTrack()) && count < 100) {
            Thread.sleep(50);
            count ++;
        }
        Assert.assertEquals(expectedTrack, getTrack());
    }

    private static class DispatchingGenericServlet extends GenericServlet {

        private static final long serialVersionUID = 1L;
        private static final String CUSTOM_REQ_RESP = "crr";
        private static final String EMPTY_DISPATCH = "empty";

        @Override
        public void service(ServletRequest req, ServletResponse resp)
                throws ServletException, IOException {
            if (DispatcherType.ASYNC != req.getDispatcherType()) {
                AsyncContext asyncContext;
                if ("y".equals(req.getParameter(CUSTOM_REQ_RESP))) {
                    asyncContext = req.startAsync(
                            new ServletRequestWrapper(req),
                            new ServletResponseWrapper(resp));
                } else {
                    asyncContext = req.startAsync();
                }
                if ("y".equals(req.getParameter(EMPTY_DISPATCH))) {
                    asyncContext.dispatch();
                } else {
                    asyncContext.dispatch("/target");
                }
                try {
                    asyncContext.dispatch("/nonExistingServlet");
                    track("FAIL");
                } catch (IllegalStateException e) {
                    track("OK");
                }
            } else {
                track("DispatchingGenericServletGet-");
            }
        }
    }


    @Test
    public void testGetRequestISE() throws Exception {
        doTestAsyncISE(true);
    }


    @Test
    public void testGetResponseISE() throws Exception {
        doTestAsyncISE(false);
    }


    private void doTestAsyncISE(boolean useGetRequest) throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        AsyncISEServlet servlet = new AsyncISEServlet();

        Wrapper w = Tomcat.addServlet(ctx, "AsyncISEServlet", servlet);
        w.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/test", "AsyncISEServlet");

        tomcat.start();

        ByteChunk response = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() +"/test", response,
                null);

        Assert.assertEquals(HttpServletResponse.SC_OK, rc);

        boolean hasIse = false;
        try {
            if (useGetRequest) {
                servlet.getAsyncContext().getRequest();
            } else {
                servlet.getAsyncContext().getResponse();
                }
        } catch (IllegalStateException ise) {
            hasIse = true;
        }

        Assert.assertTrue(hasIse);
    }


    /**
     * Accessing the AsyncContext in this way is an ugly hack that should never
     * be used in a real application since it is not thread safe. That said, it
     * is this sort of hack that the ISE is meant to be preventing.
     *
     */
    private static class AsyncISEServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private transient AsyncContext asyncContext;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            resp.setContentType("text/plain;UTF-8");

            asyncContext = req.startAsync();
            // This will commit the response
            asyncContext.complete();
        }

        public AsyncContext getAsyncContext() {
            return asyncContext;
        }
    }


    @Test
    public void testDispatchWithCustomRequestResponse() throws Exception {
        prepareApplicationWithGenericServlet("");

        StringBuilder expected = new StringBuilder();
        expected.append("OK");
        expected.append("CustomGenericServletGet-");
        requestApplicationWithGenericServlet("/dispatch?crr=y", expected);

        expected = new StringBuilder();
        expected.append("OK");
        expected.append("DispatchingGenericServletGet-");
        requestApplicationWithGenericServlet("/dispatch?crr=y&empty=y",
                expected);
    }

    private static class CustomGenericServlet extends GenericServlet {

        private static final long serialVersionUID = 1L;

        @Override
        public void service(ServletRequest req, ServletResponse res)
                throws ServletException, IOException {
            if (req instanceof ServletRequestWrapper
                    && res instanceof ServletResponseWrapper) {
                track("CustomGenericServletGet-");
            }
        }

    }

    @Test
    public void testEmptyDispatch() throws Exception {
        prepareApplicationWithGenericServlet("/fo o");
        StringBuilder expected = new StringBuilder();
        expected.append("OK");
        expected.append("DispatchingGenericServletGet-");
        requestApplicationWithGenericServlet("/fo%20o/dispatch?empty=y",
                expected);
        requestApplicationWithGenericServlet("//fo%20o/dispatch?empty=y",
                expected);
        requestApplicationWithGenericServlet("/./fo%20o/dispatch?empty=y",
                expected);
        requestApplicationWithGenericServlet("/fo%20o//dispatch?empty=y",
                expected);
        requestApplicationWithGenericServlet("/fo%20o/./dispatch?empty=y",
                expected);
        requestApplicationWithGenericServlet("/fo%20o/c/../dispatch?empty=y",
                expected);
    }

    @Test
    public void testEmptyDispatchWithCustomRequestResponse() throws Exception {
        prepareApplicationWithGenericServlet("/fo o");
        StringBuilder expected = new StringBuilder();
        expected.append("OK");
        expected.append("DispatchingGenericServletGet-");
        requestApplicationWithGenericServlet("/fo%20o/dispatch?crr=y&empty=y",
                expected);
        requestApplicationWithGenericServlet("//fo%20o/dispatch?crr=y&empty=y",
                expected);
        requestApplicationWithGenericServlet(
                "/./fo%20o/dispatch?crr=y&empty=y", expected);
        requestApplicationWithGenericServlet("/fo%20o//dispatch?crr=y&empty=y",
                expected);
        requestApplicationWithGenericServlet(
                "/fo%20o/./dispatch?crr=y&empty=y", expected);
        requestApplicationWithGenericServlet(
                "/fo%20o/c/../dispatch?crr=y&empty=y", expected);
    }

    private void prepareApplicationWithGenericServlet(String contextPath)
            throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext(contextPath, null);
        ctx.setAllowMultipleLeadingForwardSlashInPath(true);

        DispatchingGenericServlet dispatch = new DispatchingGenericServlet();
        Wrapper wrapper = Tomcat.addServlet(ctx, "dispatch", dispatch);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/dispatch", "dispatch");

        CustomGenericServlet customGeneric = new CustomGenericServlet();
        Wrapper wrapper2 = Tomcat.addServlet(ctx, "customGeneric",
                customGeneric);
        wrapper2.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/target", "customGeneric");

        tomcat.start();
    }

    private void requestApplicationWithGenericServlet(String path,
            StringBuilder expectedContent) throws Exception {
        resetTracker();
        getUrl("http://localhost:" + getPort() + path);

        // Request may complete before listener has finished processing so wait
        // up to 5 seconds for the right response
        String expectedTrack = expectedContent.toString();
        int count = 0;
        while (!expectedTrack.equals(getTrack()) && count < 100) {
            Thread.sleep(50);
            count ++;
        }
        Assert.assertEquals(expectedTrack, getTrack());
    }


    // https://bz.apache.org/bugzilla/show_bug.cgi?id=57326
    @Test
    public void testAsyncContextListenerClearing() throws Exception {
        resetTracker();

        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        Servlet stage1 = new DispatchingServletTracking("/stage2", true);
        Wrapper wrapper1 = Tomcat.addServlet(ctx, "stage1", stage1);
        wrapper1.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/stage1", "stage1");

        Servlet stage2 = new DispatchingServletTracking("/stage3", false);
        Wrapper wrapper2 = Tomcat.addServlet(ctx, "stage2", stage2);
        wrapper2.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/stage2", "stage2");

        Servlet stage3 = new NonAsyncServlet();
        Tomcat.addServlet(ctx, "stage3", stage3);
        ctx.addServletMappingDecoded("/stage3", "stage3");

        TesterAccessLogValve alv = new TesterAccessLogValve();
        ctx.getPipeline().addValve(alv);

        tomcat.start();

        getUrl("http://localhost:" + getPort()+ "/stage1");

        Assert.assertEquals("doGet-startAsync-doGet-startAsync-onStartAsync-NonAsyncServletGet-onComplete-", getTrack());

        // Check the access log
        alv.validateAccessLog(1, 200, 0, REQUEST_TIME);
    }

    private static class DispatchingServletTracking extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private final String target;
        private final boolean addTrackingListener;

        DispatchingServletTracking(String target, boolean addTrackingListener) {
            this.target = target;
            this.addTrackingListener = addTrackingListener;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            track("doGet-startAsync-");
            AsyncContext ac = req.startAsync();
            if (addTrackingListener) {
                ac.addListener(new StickyTrackingListener(false, false, null));
            }
            ac.dispatch(target);
         }
    }

    // https://bz.apache.org/bugzilla/show_bug.cgi?id=57559
    @Test
    public void testAsyncRequestURI_24() throws Exception {
        // '$' is permitted in a path
        doTestAsyncRequestURI("/foo/$/bar");
    }


    // https://bz.apache.org/bugzilla/show_bug.cgi?id=60722
    @Test
    public void testAsyncRequestURI_25() throws Exception {
        doTestAsyncRequestURI("/foo/%25/bar");
    }


    private void doTestAsyncRequestURI(String uri) throws Exception{
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        Servlet servlet = new AsyncRequestUriServlet();
        Wrapper wrapper1 = Tomcat.addServlet(ctx, "bug57559", servlet);
        wrapper1.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "bug57559");

        tomcat.start();

        ByteChunk body = getUrl("http://localhost:" + getPort() + uri);

        Assert.assertEquals(uri, body.toString());
    }

    private static class AsyncRequestUriServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            if (DispatcherType.ASYNC.equals(req.getDispatcherType())) {
                resp.setContentType("text/plain");
                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().write(req.getRequestURI());
            } else {
                req.startAsync().dispatch();
            }
        }
    }

    @Test
    public void testDispatchFromOtherContainerThread() throws Exception {
        resetTracker();
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        NonAsyncServlet nonAsyncServlet = new NonAsyncServlet();
        Tomcat.addServlet(ctx, "nonAsyncServlet", nonAsyncServlet);
        ctx.addServletMappingDecoded("/target", "nonAsyncServlet");

        AsyncStashServlet asyncStashServlet = new AsyncStashServlet();
        Wrapper w1 = Tomcat.addServlet(ctx, "asyncStashServlet", asyncStashServlet);
        w1.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/asyncStashServlet", "asyncStashServlet");

        AsyncRetrieveServlet asyncRetrieveServlet = new AsyncRetrieveServlet();
        Wrapper w2 = Tomcat.addServlet(ctx, "asyncRetrieveServlet", asyncRetrieveServlet);
        w2.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/asyncRetrieveServlet", "asyncRetrieveServlet");

        tomcat.start();

        // First request in separate thread because the response won't be
        // written until after the second request has been made.
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    getUrl("http://localhost:" + getPort() + "/asyncStashServlet");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();

        // Wait for first request to get as far as it can
        int count = 0;
        while (count < 100 && getTrack() != null &&
                !getTrack().startsWith("AsyncStashServletGet-")) {
            count++;
            Thread.sleep(100);
        }

        getUrl("http://localhost:" + getPort() + "/asyncRetrieveServlet");

        // Wait for second request to release first and allow it to complete
        String expectedTrack = "AsyncStashServletGet-AsyncRetrieveServletGet-NonAsyncServletGet-";
        count = 0;
        while (count < 100 && !getTrack().equals(expectedTrack)) {
            count++;
            Thread.sleep(100);
        }

        Assert.assertEquals(expectedTrack, getTrack());
    }

    private static class AsyncStashServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;
        private static final String DEFAULT_KEY = "DEFAULT";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            String key = req.getParameter("key");
            if (key == null) {
                key = DEFAULT_KEY;
            }

            req.getServletContext().setAttribute(key, req.startAsync());
            track("AsyncStashServletGet-");
        }
    }

    private static class AsyncRetrieveServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
        private static final String DEFAULT_KEY = "DEFAULT";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            String key = req.getParameter("key");
            if (key == null) {
                key = DEFAULT_KEY;
            }

            AsyncContext ac = (AsyncContext) req.getServletContext().getAttribute(key);
            if (ac == null) {
                track("FAIL:nullAsyncContext-");
            } else {
                track("AsyncRetrieveServletGet-");
                ac.dispatch("/target");
            }
        }
    }


    /*
     * See https://bz.apache.org/bugzilla/show_bug.cgi?id=58751 comment 1
     */
    @Test
    public void testTimeoutDispatchCustomErrorPage() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context context = tomcat.addContext("", null);
        tomcat.addServlet("", "timeout", Bug58751AsyncServlet.class.getName())
                .setAsyncSupported(true);
        CustomErrorServlet customErrorServlet = new CustomErrorServlet();
        Tomcat.addServlet(context, "customErrorServlet", customErrorServlet);
        context.addServletMappingDecoded("/timeout", "timeout");
        context.addServletMappingDecoded("/error", "customErrorServlet");
        ErrorPage errorPage = new ErrorPage();
        errorPage.setLocation("/error");
        context.addErrorPage(errorPage);
        tomcat.start();

        ByteChunk responseBody = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + "/timeout", responseBody, null);

        Assert.assertEquals(503, rc);
        Assert.assertEquals(CustomErrorServlet.ERROR_MESSAGE, responseBody.toString());
    }


    public static class Bug58751AsyncServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            if (req.getAttribute("timeout") != null) {
                resp.sendError(503);
            } else {
                final AsyncContext context = req.startAsync();
                context.setTimeout(5000);
                context.addListener(new AsyncListener() {

                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException {
                        HttpServletResponse response = (HttpServletResponse) event
                                .getSuppliedResponse();
                        if (!response.isCommitted()) {
                            event.getSuppliedRequest()
                                    .setAttribute("timeout", Boolean.TRUE);
                            context.dispatch();
                        }
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) throws IOException {
                    }

                    @Override
                    public void onError(AsyncEvent event) throws IOException {
                    }

                    @Override
                    public void onComplete(AsyncEvent event) throws IOException {
                    }
                });
            }
        }

    }


    @Test
    public void testAsyncListenerSupplyRequestResponse() {
        final ServletRequest servletRequest = EasyMock.createMock(ServletRequest.class);
        final ServletResponse servletResponse = EasyMock.createMock(ServletResponse.class);
        final AsyncListener listener = new AsyncListener() {

            @Override
            public void onTimeout(AsyncEvent event) throws IOException {
                checkRequestResponse(event);
            }

            @Override
            public void onStartAsync(AsyncEvent event) throws IOException {
                checkRequestResponse(event);
            }

            @Override
            public void onError(AsyncEvent event) throws IOException {
                checkRequestResponse(event);
            }

            @Override
            public void onComplete(AsyncEvent event) throws IOException {
                checkRequestResponse(event);
            }

            private void checkRequestResponse(AsyncEvent event) {
                Assert.assertEquals(servletRequest, event.getSuppliedRequest());
                Assert.assertEquals(servletResponse, event.getSuppliedResponse());
            }
        };
        final Context context = new TesterContext();
        final Response response = new Response();
        final Request request = new Request(null);
        request.setCoyoteRequest(new org.apache.coyote.Request());
        request.getMappingData().context = context;
        final AsyncContextImpl ac = new AsyncContextImpl(request);
        ac.addListener(listener, servletRequest, servletResponse);
        ac.setStarted(context, request, response, true);
        ac.addListener(listener, servletRequest, servletResponse);
        ac.setErrorState(new Exception(), true);
        ac.fireOnComplete();
    }


    /*
     * https://bz.apache.org/bugzilla/show_bug.cgi?id=59317
     */
    @Test
    public void testAsyncDispatchUrlWithSpaces() throws Exception {
        doTestDispatchWithSpaces(true);
    }


    @Test
    public void testForwardDispatchUrlWithSpaces() throws Exception {
        doTestDispatchWithSpaces(false);
    }


    private void doTestDispatchWithSpaces(boolean async) throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context context = tomcat.addContext("", null);
        if (async) {
            Servlet s = new AsyncDispatchUrlWithSpacesServlet();
            Wrapper w = Tomcat.addServlet(context, "space", s);
            w.setAsyncSupported(true);
        } else {
            Tomcat.addServlet(context, "space", new ForwardDispatchUrlWithSpacesServlet());
        }
        context.addServletMappingDecoded("/space/*", "space");
        tomcat.start();

        ByteChunk responseBody = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + "/sp%61ce/foo%20bar", responseBody, null);

        Assert.assertEquals(200, rc);
    }


    private static class AsyncDispatchUrlWithSpacesServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            Integer countObj = (Integer) req.getAttribute("count");
            int count = 0;
            if (countObj != null) {
                count = countObj.intValue();
            }
            count++;
            req.setAttribute("count", Integer.valueOf(count));

            String encodedUri = req.getRequestURI();

            try {
                // Just here to trigger the error
                @SuppressWarnings("unused")
                URI u = new URI(encodedUri);
            } catch (URISyntaxException e) {
                throw new ServletException(e);
            }

            if (count > 3) {
                resp.setContentType("text/plain");
                resp.getWriter().print("OK");
            } else {
                AsyncContext ac = req.startAsync();
                ac.dispatch(encodedUri);
            }
        }
    }


    private static class ForwardDispatchUrlWithSpacesServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            Integer countObj = (Integer) req.getAttribute("count");
            int count = 0;
            if (countObj != null) {
                count = countObj.intValue();
            }
            count++;
            req.setAttribute("count", Integer.valueOf(count));

            String encodedUri = req.getRequestURI();

            try {
                // Just here to trigger the error
                @SuppressWarnings("unused")
                URI u = new URI(req.getRequestURI());
            } catch (URISyntaxException e) {
                throw new ServletException(e);
            }

            if (count > 3) {
                resp.setContentType("text/plain");
                resp.getWriter().print("OK");
            } else {
                RequestDispatcher rd = req.getRequestDispatcher(encodedUri);
                rd.forward(req, resp);
            }
        }
    }


    @Test
    public void testBug61185() throws Exception {
        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        tomcat.getConnector().setEncodedSolidusHandling(EncodedSolidusHandling.DECODE.getValue());

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        EncodedDispatchServlet encodedDispatchServlet = new EncodedDispatchServlet();
        Wrapper wrapper = Tomcat.addServlet(ctx, "encodedDispatchServlet", encodedDispatchServlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/*", "encodedDispatchServlet");

        tomcat.start();

        ByteChunk body = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + EncodedDispatchServlet.ENCODED_URI, body, null);

        Assert.assertEquals(HttpServletResponse.SC_OK, rc);
        Assert.assertEquals("OK", body.toString());
    }


    private static final class EncodedDispatchServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private static final String ENCODED_URI = "/foo/vv%2F1234/add/2";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            if (DispatcherType.ASYNC == req.getDispatcherType()) {
                if (ENCODED_URI.equals(req.getRequestURI())) {
                    resp.getWriter().print("OK");
                } else {
                    resp.getWriter().print("FAIL");
                }
            } else {
                AsyncContext ac = req.startAsync();
                ac.dispatch();
            }
        }

    }


    @Test
    public void testAsyncIoEnd00() throws Exception {
        doTestAsyncIoEnd(false, false);
    }


    @Test
    public void testAsyncIoEnd01() throws Exception {
        doTestAsyncIoEnd(false, true);
    }


    @Test
    public void testAsyncIoEnd02() throws Exception {
        doTestAsyncIoEnd(true, false);
    }


    @Test
    public void testAsyncIoEnd03() throws Exception {
        doTestAsyncIoEnd(true, true);
    }


    private void doTestAsyncIoEnd(boolean useThread, boolean useComplete) throws Exception {
        LogManager.getLogManager().getLogger("org.apache.coyote").setLevel(Level.ALL);
        try {
            Tomcat tomcat = getTomcatInstance();

            // No file system docBase required
            Context ctx = tomcat.addContext("", null);

            AsyncIoEndServlet asyncIoEndServlet = new AsyncIoEndServlet(useThread, useComplete);
            Wrapper wrapper = Tomcat.addServlet(ctx, "asyncIoEndServlet", asyncIoEndServlet);
            wrapper.setAsyncSupported(true);
            ctx.addServletMappingDecoded("/asyncIoEndServlet", "asyncIoEndServlet");

            SimpleServlet simpleServlet = new SimpleServlet();
            Tomcat.addServlet(ctx, "simpleServlet", simpleServlet);
            ctx.addServletMappingDecoded("/simpleServlet", "simpleServlet");

            tomcat.start();

            ByteChunk body = new ByteChunk();
            int rc = getUrl("http://localhost:" + getPort() + "/asyncIoEndServlet", body, null);

            Assert.assertEquals(HttpServletResponse.SC_OK, rc);
            Assert.assertEquals("OK", body.toString());

            Assert.assertFalse(asyncIoEndServlet.getInvalidStateDetected());
        } finally {
            LogManager.getLogManager().getLogger("org.apache.coyote").setLevel(Level.INFO);
        }
    }


    private static class AsyncIoEndServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private final boolean useThread;
        private final boolean useComplete;
        private transient AsyncIoEndWriteListener asyncIoEndWriteListener;

        AsyncIoEndServlet(boolean useThread, boolean useComplete) {
            this.useThread = useThread;
            this.useComplete = useComplete;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            if (useComplete) {
                // Write expected body here
                resp.setContentType("text/plain");
                resp.setCharacterEncoding("UTF-8");
                resp.getOutputStream().write("OK".getBytes(StandardCharsets.UTF_8));
            }
            AsyncContext ac = req.startAsync();
            ServletOutputStream sos = resp.getOutputStream();
            asyncIoEndWriteListener = new AsyncIoEndWriteListener(ac, useThread, useComplete);
            sos.setWriteListener(asyncIoEndWriteListener);
        }

        public boolean getInvalidStateDetected() {
            if (asyncIoEndWriteListener != null) {
                return asyncIoEndWriteListener.getInvalidStateDetected();
            }
            return false;
        }
    }


    private static class AsyncIoEndWriteListener implements WriteListener {

        private final AsyncContext ac;
        private final boolean useThread;
        private final boolean useComplete;
        private boolean invalidStateDetected = false;

        AsyncIoEndWriteListener(AsyncContext ac, boolean useThread,
                boolean useComplete) {
            this.ac = ac;
            this.useThread = useThread;
            this.useComplete = useComplete;
        }


        @Override
        public void onWritePossible() throws IOException {
            if (useThread) {
                (new Thread() {
                    @Override
                    public void run() {
                        doOnWritePossible();
                    }
                }).start();
            } else {
                doOnWritePossible();
            }
        }


        public void doOnWritePossible() {
            // Hack to avoid ISE if we try getting the request after complete/dispatch
            ServletRequest req = ac.getRequest();
            if (useComplete) {
                ac.complete();
            } else {
                ac.dispatch("/simpleServlet");
            }
            if (!useThread && req.isAsyncStarted()) {
                invalidStateDetected = true;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            throw new RuntimeException(throwable);
        }


        public boolean getInvalidStateDetected() {
            return invalidStateDetected;
        }
    }


    private static class SimpleServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            // Write expected body here
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");
            resp.getOutputStream().write("OK".getBytes(StandardCharsets.UTF_8));
        }
    }


    /*
     * Tests an error on an async thread before the container thread that called
     * startAsync() has returned to the container.
     *
     * Required sequence is:
     * - enter Servlet's service() method
     * - startAsync()
     * - start async thread
     * - close client connection
     * - write on async thread -> I/O error
     * - exit Servlet's service() method
     *
     * This test makes extensive use of instance fields in the Servlet that
     * would normally be considered very poor practice. It is only safe in this
     * test as the Servlet only processes a single request.
     */
    @Test
    public void testBug63816() throws Exception {
        CountDownLatch doGetLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        CountDownLatch threadCompleteLatch = new CountDownLatch(1);

        AtomicBoolean ise = new AtomicBoolean(true);

        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        Bug63816Servlet bug63816Servlet = new Bug63816Servlet(doGetLatch, clientCloseLatch, threadCompleteLatch, ise);
        Wrapper wrapper = Tomcat.addServlet(ctx, "bug63816Servlet", bug63816Servlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/*", "bug63816Servlet");

        tomcat.start();

        Bug63816Client client = new Bug63816Client();
        client.setPort(getPort());
        client.setRequest(new String[] { "GET / HTTP/1.1" + SimpleHttpClient.CRLF +
                                         "Host: localhost:" + SimpleHttpClient.CRLF +
                                         SimpleHttpClient.CRLF});
        client.connect();
        client.sendRequest();

        // Wait for async to start
        doGetLatch.await();

        client.disconnect();

        clientCloseLatch.countDown();

        threadCompleteLatch.await();

        Assert.assertFalse(ise.get());
    }


    private static final class Bug63816Client extends SimpleHttpClient {

        @Override
        public boolean isResponseBodyOK() {
            return true;
        }
    }


    private static final class Bug63816Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private final transient CountDownLatch doGetLatch;
        private final transient CountDownLatch clientCloseLatch;
        private final transient CountDownLatch threadCompleteLatch;
        private final AtomicBoolean ise;

        Bug63816Servlet(CountDownLatch doGetLatch, CountDownLatch clientCloseLatch,
                CountDownLatch threadCompleteLatch, AtomicBoolean ise) {
            this.doGetLatch = doGetLatch;
            this.clientCloseLatch = clientCloseLatch;
            this.threadCompleteLatch = threadCompleteLatch;
            this.ise = ise;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            doGetLatch.countDown();

            AsyncContext ac = req.startAsync();
            Thread t = new Bug63816Thread(ac, clientCloseLatch, threadCompleteLatch, ise);
            t.start();

            try {
                threadCompleteLatch.await();
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }


    private static final class Bug63816Thread extends Thread {

        private final AsyncContext ac;
        private final CountDownLatch clientCloseLatch;
        private final CountDownLatch threadCompleteLatch;
        private final AtomicBoolean ise;

        Bug63816Thread(AsyncContext ac, CountDownLatch clientCloseLatch, CountDownLatch threadCompleteLatch,
                AtomicBoolean ise) {
            this.ac = ac;
            this.clientCloseLatch = clientCloseLatch;
            this.threadCompleteLatch = threadCompleteLatch;
            this.ise = ise;
        }

        @Override
        public void run() {
            try {
                // Wait for client to close connection
                clientCloseLatch.await();

                try {
                    ServletResponse resp = ac.getResponse();
                    resp.setContentType("text/plain");
                    for (int i = 0; i < 4; i++) {
                        resp.getWriter().write(TestCoyoteAdapter.TEXT_8K);
                        resp.flushBuffer();
                    }
                } catch (IOException e) {
                    // Ignore
                }
                ise.set(false);
            } catch (InterruptedException e) {
                // Ignore
            } finally {
                threadCompleteLatch.countDown();
            }
        }
    }


    @Test
    public void testCanceledPostChunked() throws Exception {
        doTestCanceledPost(new String[] {
                "POST / HTTP/1.1" + SimpleHttpClient.CRLF +
                "Host: localhost:" + SimpleHttpClient.CRLF +
                "Transfer-Encoding: Chunked" + SimpleHttpClient.CRLF +
                SimpleHttpClient.CRLF +
                "10" + SimpleHttpClient.CRLF +
                "This is 16 bytes" + SimpleHttpClient.CRLF
                });
    }


    @Test
    public void testCanceledPostNoChunking() throws Exception {
        doTestCanceledPost(new String[] {
                "POST / HTTP/1.1" + SimpleHttpClient.CRLF +
                "Host: localhost:" + SimpleHttpClient.CRLF +
                "Content-Length: 100" + SimpleHttpClient.CRLF +
                SimpleHttpClient.CRLF +
                "This is 16 bytes"
                });
    }


    /*
     * Tests an error on an async thread when the client closes the connection
     * before fully writing the request body.
     *
     * Required sequence is:
     * - enter Servlet's service() method
     * - startAsync()
     * - start async thread
     * - read partial body
     * - close client connection
     * - read on async thread -> I/O error
     * - exit Servlet's service() method
     *
     * This test makes extensive use of instance fields in the Servlet that
     * would normally be considered very poor practice. It is only safe in this
     * test as the Servlet only processes a single request.
     */
    private void doTestCanceledPost(String[] request) throws Exception {
        CountDownLatch partialReadLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        CountDownLatch threadCompleteLatch = new CountDownLatch(1);

        AtomicBoolean testFailed = new AtomicBoolean(true);

        // Setup Tomcat instance
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        PostServlet postServlet = new PostServlet(partialReadLatch, clientCloseLatch, threadCompleteLatch, testFailed);
        Wrapper wrapper = Tomcat.addServlet(ctx, "postServlet", postServlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/*", "postServlet");

        tomcat.start();

        PostClient client = new PostClient();
        client.setPort(getPort());
        client.setRequest(request);
        client.connect();
        client.sendRequest();

        // Wait server to read partial request body
        partialReadLatch.await();

        client.disconnect();

        clientCloseLatch.countDown();

        threadCompleteLatch.await();

        Assert.assertFalse(testFailed.get());
    }


    private static final class PostClient extends SimpleHttpClient {

        @Override
        public boolean isResponseBodyOK() {
            return true;
        }
    }


    private static final class PostServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private final transient CountDownLatch partialReadLatch;
        private final transient CountDownLatch clientCloseLatch;
        private final transient CountDownLatch threadCompleteLatch;
        private final AtomicBoolean testFailed;

        PostServlet(CountDownLatch doPostLatch, CountDownLatch clientCloseLatch,
                CountDownLatch threadCompleteLatch, AtomicBoolean testFailed) {
            this.partialReadLatch = doPostLatch;
            this.clientCloseLatch = clientCloseLatch;
            this.threadCompleteLatch = threadCompleteLatch;
            this.testFailed = testFailed;
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            AsyncContext ac = req.startAsync();
            Thread t = new PostServletThread(ac, partialReadLatch, clientCloseLatch, threadCompleteLatch, testFailed);
            t.start();

            try {
                threadCompleteLatch.await();
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }


    private static final class PostServletThread extends Thread {

        private final AsyncContext ac;
        private final CountDownLatch partialReadLatch;
        private final CountDownLatch clientCloseLatch;
        private final CountDownLatch threadCompleteLatch;
        private final AtomicBoolean testFailed;

        PostServletThread(AsyncContext ac, CountDownLatch partialReadLatch, CountDownLatch clientCloseLatch,
                CountDownLatch threadCompleteLatch, AtomicBoolean testFailed) {
            this.ac = ac;
            this.partialReadLatch = partialReadLatch;
            this.clientCloseLatch = clientCloseLatch;
            this.threadCompleteLatch = threadCompleteLatch;
            this.testFailed = testFailed;
        }

        @Override
        public void run() {
            try {
                int bytesRead = 0;
                byte[] buffer = new byte[32];
                InputStream is = null;

                try {
                    is = ac.getRequest().getInputStream();

                    // Read the partial request body
                    while (bytesRead < 16) {
                        int read = is.read(buffer);
                        if (read == -1) {
                            // Error condition
                            return;
                        }
                        bytesRead += read;
                    }
                } catch (IOException ioe) {
                    // Error condition
                    return;
                } finally {
                    partialReadLatch.countDown();
                }

                // Wait for client to close connection
                clientCloseLatch.await();

                // Read again
                try {
                    is.read();
                } catch (IOException e) {
                    e.printStackTrace();
                    // Required. Clear the error marker.
                    testFailed.set(false);
                }
            } catch (InterruptedException e) {
                // Ignore
            } finally {
                threadCompleteLatch.countDown();
            }
        }
    }
}
