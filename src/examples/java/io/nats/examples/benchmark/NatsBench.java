// Copyright 2015-2018 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.examples.benchmark;

import io.nats.examples.benchmark.Benchmark;
import io.nats.examples.benchmark.Sample;
import io.nats.client.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A utility class for measuring NATS performance, similar to the version in go and node.
 * The various tradeoffs to make this code act/work like the other versions, including the
 * previous java version, make it a bit &quot;crufty&quot; for an example. See autobench for
 * an example with minimal boilerplate.
 */
public class NatsBench {
    final BlockingQueue<Throwable> errorQueue = new LinkedBlockingQueue<Throwable>();

    // Default test values
    private int numMsgs = 5_000_000;
    private int numPubs = 1;
    private int numSubs = 0;
    private int size = 128;

    private String urls = Options.DEFAULT_URL;
    private String subject;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private boolean csv = false;

    private Thread shutdownHook;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private boolean secure = false;
    private Benchmark bench;

    static final String usageString =
            "\nUsage: java NatsBench [-s server] [-tls] [-np num] [-ns num] [-n num] [-ms size] "
                    + "[-csv file] <subject>\n\nOptions:\n"
                    + "    -s   <urls>                    The nats server URLs (comma-separated), use tls:// or opentls:// to require tls\n"
                    + "    -np                             Number of concurrent publishers (1)\n"
                    + "    -ns                             Number of concurrent subscribers (0)\n"
                    + "    -n                              Number of messages to publish (100,000)\n"
                    + "    -ms                             Size of the message (128)\n"
                    + "    -csv                            Print results to stdout as csv (false)\n";

    public NatsBench(String[] args) throws Exception {
        if (args == null || args.length < 1) {
            usage();
            return;
        }
        parseArgs(args);
    }

    public NatsBench(Properties properties) throws NoSuchAlgorithmException {
        urls = properties.getProperty("bench.nats.servers", urls);
        secure = Boolean.parseBoolean(
                properties.getProperty("bench.nats.secure", Boolean.toString(secure)));
        numMsgs = Integer.parseInt(
                properties.getProperty("bench.nats.msg.count", Integer.toString(numMsgs)));
        size = Integer
                .parseInt(properties.getProperty("bench.nats.msg.size", Integer.toString(numSubs)));
        numPubs = Integer
                .parseInt(properties.getProperty("bench.nats.pubs", Integer.toString(numPubs)));
        numSubs = Integer
                .parseInt(properties.getProperty("bench.nats.subs", Integer.toString(numSubs)));
        csv = Boolean.parseBoolean(
            properties.getProperty("bench.nats.csv", Boolean.toString(csv)));
        subject = properties.getProperty("bench.nats.subject", NUID.nextGlobal());
    }

    Options prepareOptions(boolean secure) throws NoSuchAlgorithmException {
        String[] servers = urls.split(",");
        Options.Builder builder = new Options.Builder();
        builder.noReconnect();
        builder.connectionName("NatsBench");
        builder.servers(servers);
        //builder.turnOnAdvancedStats();

        if (secure) {
            builder.secure();
        }

        return builder.build();
    }

    class Worker implements Runnable {
        final Future<Boolean> starter;
        final Phaser finisher;
        final int numMsgs;
        final int size;
        final boolean secure;

        Worker(Future<Boolean> starter, Phaser finisher, int numMsgs, int size, boolean secure) {
            this.starter = starter;
            this.finisher = finisher;
            this.numMsgs = numMsgs;
            this.size = size;
            this.secure = secure;
        }

        @Override
        public void run() {
        }
    }

    class SyncSubWorker extends Worker {
        final Phaser subReady;
        SyncSubWorker(Future<Boolean> starter, Phaser subReady, Phaser finisher, int numMsgs, int size, boolean secure) {
            super(starter, finisher, numMsgs, size, secure);
            this.subReady = subReady;
        }

        @Override
        public void run() {
            try {
                Options opts = prepareOptions(this.secure);
                Connection nc = Nats.connect(opts);

                Subscription sub = nc.subscribe(subject);
                nc.flush(null);

                // Signal we are ready
                subReady.arrive();

                // Wait for the signal to start tracking time
                starter.get(60, TimeUnit.SECONDS);

                Duration timeout = Duration.ofMillis(1000);

                int receivedCount = 0;
                long start = System.nanoTime();
                while (receivedCount < numMsgs) {
                    if(sub.nextMessage(timeout) != null) {
                        received.incrementAndGet();
                        receivedCount++;
                    }
                }
                long end = System.nanoTime();

                bench.addSubSample(new Sample(numMsgs, size, start, end, nc.getStatistics()));

                //System.out.println(nc.getStatistics());

                // Clean up
                sub.unsubscribe();
                nc.close();
            } catch (Exception e) {
                errorQueue.add(e);
            } finally {
                subReady.arrive();
                finisher.arrive();
            }
        }
    }

    class PubWorker extends Worker {

        private AtomicLong start;
        private long targetPubRate;

        PubWorker(Future<Boolean> starter, Phaser finisher, int numMsgs, int size, boolean secure, long targetPubRate) {
            super(starter, finisher, numMsgs, size, secure);
            this.start = new AtomicLong();
            this.targetPubRate = targetPubRate;
        }

        @Override
        public void run() {
            try {
                Options opts = prepareOptions(this.secure);
                Connection nc = Nats.connect(opts);

                byte[] payload = null;
                if (size > 0) {
                    payload = new byte[size];
                }

                 // Wait for the signal
                starter.get(60, TimeUnit.SECONDS);
                start.set(System.nanoTime());
                for (int i = 0; i < numMsgs; i++) {
                    nc.publish(subject, payload);
                    sent.incrementAndGet();
                }
                nc.flush(Duration.ofSeconds(5));
                long end = System.nanoTime();

                bench.addPubSample(new Sample(numMsgs, size, start.get(), end, nc.getStatistics()));
                nc.close();
            } catch (Exception e) {
                errorQueue.add(e);
            } finally {
                finisher.arrive();
            }
        }


        void adjustAndSleep(Connection nc) throws InterruptedException {

            if (this.targetPubRate <= 0) {
                return;
            }

            long count = sent.incrementAndGet();

            if (count % 1000 != 0) { // Only sleep every 1000 message
                return;
            }

            long now = System.nanoTime();
            long start = this.start.get();
            double rate = (1e9 * (double) count)/((double)(now - start));
            double delay = (1.0/((double) this.targetPubRate));
            double adjust = delay / 20.0; // 5%
            
            if (adjust == 0) {
                adjust = 1e-9; // 1ns min
            }
            
            if (rate < this.targetPubRate) {
                delay -= adjust;
            } else if (rate > this.targetPubRate) {
                delay += adjust;
            }
            
            if (delay < 0) {
                delay = 0;
            }

            delay = delay * 1000; // we are doing this every 1000 messages
            
            long nanos = (long)(delay * 1e9);
            LockSupport.parkNanos(nanos);
            
            // Flush small messages regularly
            if (this.size < 64 && count != 0 && count % 100_000 == 0) {
                try {nc.flush(Duration.ofSeconds(5));}catch(Exception e){}
            }
        }
    }

    /**
     * Runs the benchmark.
     *
     * @throws Exception if an exception occurs
     */
    public void start() throws Exception {
        installShutdownHook();

        System.out.println();
        System.out.printf("Starting benchmark(s) [msgs=%d, msgsize=%d, pubs=%d, subs=%d]\n", numMsgs,
                size, numPubs, numSubs);
        System.out.printf("Current memory usage is %s / %s / %s free/total/max\n", 
                            Utils.humanBytes(Runtime.getRuntime().freeMemory()),
                            Utils.humanBytes(Runtime.getRuntime().totalMemory()),
                            Utils.humanBytes(Runtime.getRuntime().maxMemory()));
        System.out.println("Use ctrl-C to cancel.");
        System.out.println();

        if (this.numPubs > 0) {
            runTest("Pub Only", this.numPubs, 0);
            runTest("Pub/Sub", this.numPubs, this.numSubs);
        } else {
            runTest("Sub", this.numPubs, this.numSubs);
        }

        System.out.println();
        System.out.printf("Final memory usage is %s / %s / %s free/total/max\n", 
                            Utils.humanBytes(Runtime.getRuntime().freeMemory()),
                            Utils.humanBytes(Runtime.getRuntime().totalMemory()),
                            Utils.humanBytes(Runtime.getRuntime().maxMemory()));
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }

    public void runTest(String title, int pubCount, int subCount) throws Exception {
        final Phaser subReady = new Phaser();
        final Phaser finisher = new Phaser();
        final CompletableFuture<Boolean> starter = new CompletableFuture<>();
        subReady.register();
        finisher.register();
        sent.set(0);
        received.set(0);

        bench = new Benchmark(title);

        // Run Subscribers first
        for (int i = 0; i < subCount; i++) {
            subReady.register();
            finisher.register();
            new Thread(new SyncSubWorker(starter, subReady, finisher, this.numMsgs, this.size, secure), "Sub-"+i).start();
        }

        // Wait for subscribers threads to initialize
        subReady.arriveAndAwaitAdvance();

        if (!errorQueue.isEmpty()) {
            Throwable error = errorQueue.take();
            System.err.printf(error.getMessage());
            error.printStackTrace();
            throw new RuntimeException(error);
        }

        // Now publishers
        if (pubCount != 0) { // running pub in another app
            int remaining = this.numMsgs;
            int perPubMsgs = this.numMsgs / pubCount;
            for (int i = 0; i < pubCount; i++) {
                finisher.register();
                if (i == numPubs - 1) {
                    perPubMsgs = remaining;
                }
                
                if (subCount == 0) {
                    new Thread(new PubWorker(starter, finisher, perPubMsgs, this.size, secure, 0), "Pub-"+i).start();
                } else {
                    new Thread(new PubWorker(starter, finisher, perPubMsgs, this.size, secure, 2_000_000), "Pub-"+i).start();
                }
                
                remaining -= perPubMsgs;
            }
        } else {
            System.out.println("Starting subscribers, time to run the publishers somewhere ...");
        }

        // Start everything running
        starter.complete(Boolean.TRUE);

        // Wait for subscribers and publishers to finish
        finisher.arriveAndAwaitAdvance();

        if (!errorQueue.isEmpty()) {
            Throwable error = errorQueue.take();
            System.err.printf("Error running test [%s]\n", error.getMessage());
            System.err.printf("Latest test sent = %d\n", sent.get());
            System.err.printf("Latest test received = %d\n", received.get());
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            throw new RuntimeException(error);
        }

        if (subCount==1 && pubCount>0 && sent.get() != received.get()) {
            System.out.println("#### Error - sent and received are not equal "+sent.get() + " != " + received.get());
        }

        bench.close();

        if (csv) {
            System.out.println(bench.csv());
        } else {
            System.out.println(bench.report());
        }
    }

    void installShutdownHook() {
        shutdownHook = new Thread(new Runnable() {
            @Override
            public void run() {
                System.err.println("\nCaught CTRL-C, shutting down gracefully...\n");
                shutdown.set(true);
                System.err.printf("Sent=%d\n", sent.get());
                System.err.printf("Received=%d\n", received.get());

            }
        });

        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    void usage() {
        System.err.println(usageString);
        System.exit(-1);
    }

    private void parseArgs(String[] args) {
        List<String> argList = new ArrayList<String>(Arrays.asList(args));

        subject = argList.get(argList.size() - 1);
        argList.remove(argList.size() - 1);

        // Anything left is flags + args
        Iterator<String> it = argList.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "-s":
                case "--server":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    urls = it.next();
                    it.remove();
                    continue;
                case "-tls":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    secure = true;
                    continue;
                case "-np":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    numPubs = Integer.parseInt(it.next());
                    it.remove();
                    continue;
                case "-ns":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    numSubs = Integer.parseInt(it.next());
                    it.remove();
                    continue;
                case "-n":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    numMsgs = Integer.parseInt(it.next());
                    // hashModulo = numMsgs / 100;
                    it.remove();
                    continue;
                case "-ms":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    size = Integer.parseInt(it.next());
                    it.remove();
                    continue;
                case "-csv":
                    if (!it.hasNext()) {
                        usage();
                    }
                    it.remove();
                    csv = true;
                    continue;
                default:
                    System.err.printf("Unexpected token: '%s'\n", arg);
                    usage();
                    break;
            }
        }
    }

    private static Properties loadProperties(String configPath) {
        try {
            InputStream is = new FileInputStream(configPath);
            Properties prop = new Properties();
            prop.load(is);
            return prop;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The main program executive.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        Properties properties = null;
        try {
            if (args.length == 1 && args[0].endsWith(".properties")) {
                properties = loadProperties(args[0]);
                new NatsBench(properties).start();
            } else {
                new NatsBench(args).start();
            }
        } catch (Exception e) {
            System.err.printf("Exiting due to exception [%s]\n", e.getMessage());
            e.printStackTrace();
            System.exit(-1);
        }
        System.exit(0);
    }

}
