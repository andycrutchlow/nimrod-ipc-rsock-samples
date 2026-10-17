package com.nimrodtechs.samples.benchmark.common;

import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Streams samples to disk; memory does not grow with benchmark duration. RSS is sampled externally. */
public final class ResourceSampler implements AutoCloseable {
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"benchmark-resources");t.setDaemon(true);return t;});
    private final PrintWriter out;
    private final Path directory;
    private final AtomicReference<String> phase=new AtomicReference<>("startup");
    private final com.sun.management.OperatingSystemMXBean os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    private long lastTime=System.nanoTime(), lastCpu=os.getProcessCpuTime();
    private volatile boolean failed;
    public ResourceSampler(Path directory) throws IOException {
        this.directory=directory;Files.createDirectories(directory);out=new PrintWriter(Files.newBufferedWriter(directory.resolve("resources.csv")));
        out.println("epochMillis,phase,elapsedSeconds,cpuCores,cpuTimeNanos,heapUsed,heapCommitted,nonHeapUsed,directBytes,threads,gcCount,gcMillis");
        scheduler.scheduleAtFixedRate(this::sample,0,1,TimeUnit.SECONDS);
    }
    public synchronized void phase(String value) {
        sample();phase.set(value);
        try { Files.writeString(directory.resolve("phase.txt"),value); } catch(IOException e) { failed=true; }
    }
    public boolean failed() { return failed; }
    private synchronized void sample() {
        try {
            long now=System.nanoTime(),cpu=os.getProcessCpuTime();
            var memory=ManagementFactory.getMemoryMXBean();
            long direct=ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream().filter(p->p.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
            long gcCount=0,gcMillis=0;
            for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) { if(gc.getCollectionCount()>0) gcCount+=gc.getCollectionCount();if(gc.getCollectionTime()>0) gcMillis+=gc.getCollectionTime(); }
            out.printf(Locale.ROOT,"%d,%s,%.9f,%.6f,%d,%d,%d,%d,%d,%d,%d,%d%n",System.currentTimeMillis(),phase.get(),(now-lastTime)/1e9,
                (double)(cpu-lastCpu)/Math.max(1,now-lastTime),cpu,memory.getHeapMemoryUsage().getUsed(),memory.getHeapMemoryUsage().getCommitted(),
                memory.getNonHeapMemoryUsage().getUsed(),direct,ManagementFactory.getThreadMXBean().getThreadCount(),gcCount,gcMillis);
            out.flush();failed|=out.checkError(); lastTime=now;lastCpu=cpu;
        } catch(Exception e) { failed=true; }
    }
    @Override public void close() { scheduler.shutdownNow(); synchronized(this) { sample();out.close(); } }
}
