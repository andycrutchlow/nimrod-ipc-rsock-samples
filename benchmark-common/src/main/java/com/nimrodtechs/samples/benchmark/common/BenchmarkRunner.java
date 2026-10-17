package com.nimrodtechs.samples.benchmark.common;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;
import org.HdrHistogram.Recorder;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded synchronous closed-loop runner. No queued calls or retained per-call samples. */
public final class BenchmarkRunner {
    @FunctionalInterface public interface Rpc { BenchmarkMessage call(BenchmarkMessage request) throws Exception; }
    private final BenchmarkConfig config;
    private final Rpc rpc;
    private final Map<String,Object> identity;
    private final byte[] payload;
    private final long checksum;
    public BenchmarkRunner(BenchmarkConfig config, Rpc rpc, Map<String,Object> identity) {
        this.config=config;this.rpc=rpc;this.identity=identity;
        payload=Workload.payload(config.payloadBytes(),config.seed());checksum=Workload.checksum(payload);
    }
    public Map<String,Object> run() throws Exception {
        Files.createDirectories(config.output());
        Map<String,Object> manifest=new LinkedHashMap<>(Results.provenance());
        manifest.put("config",Map.of("runId",config.runId(),"payloadBytes",config.payloadBytes(),"concurrency",config.concurrency(),
                "warmupSeconds",config.warmupSeconds(),"measurementSeconds",config.measurementSeconds(),"timeoutMillis",config.timeoutMillis(),
                "drainMillis",config.drainMillis(),"seed",config.seed(),"mode","closed-loop"));
        manifest.put("implementation",identity);manifest.put("latencyBoundary","client proxy/stub invocation through mapping and full reply validation");
        manifest.put("rssSource","external process sampler; see os-resources.csv");
        Results.write(config.output().resolve("manifest.json"),manifest);
        try(ResourceSampler resources=new ResourceSampler(config.output());
            PrintWriter intervals=new PrintWriter(Files.newBufferedWriter(config.output().resolve("intervals.csv")))) {
            intervals.println("phase,elapsedSeconds,intervalSeconds,completed,completedPerSecond,p50Nanos,p95Nanos,p99Nanos,maxNanos");
            resources.phase("readiness");
            PhaseResult ready=phase("readiness",0,1,intervals,true);
            PhaseResult warmup=null,measurement=null;
            if(ready.valid()) { resources.phase("warmup");warmup=phase("warmup",config.warmupSeconds(),config.concurrency(),intervals,false); }
            if(warmup!=null && warmup.valid()) { resources.phase("measurement");measurement=phase("measurement",config.measurementSeconds(),config.concurrency(),intervals,false); }
            resources.phase("finished");
            Map<String,Object> result=new LinkedHashMap<>();
            boolean valid=ready.valid() && warmup!=null && warmup.valid() && measurement!=null && measurement.valid() && !resources.failed() && !intervals.checkError();
            result.put("valid",valid);result.put("readiness",ready.values());result.put("warmup",warmup==null?null:warmup.values());
            result.put("measurement",measurement==null?null:measurement.values());result.put("resourceSamplingError",resources.failed());
            result.put("performanceConclusion","none; compare only validated full runs under equivalent configurations");
            Results.write(config.output().resolve("summary.json"),result);return result;
        }
    }
    private record PhaseResult(boolean valid, Map<String,Object> values) { }
    private PhaseResult phase(String phase,double seconds,int concurrency,PrintWriter intervals,boolean once) throws Exception {
        AtomicBoolean stop=new AtomicBoolean();CountDownLatch ready=new CountDownLatch(concurrency),go=new CountDownLatch(1);
        AtomicLong sequence=new AtomicLong();List<Worker> workers=new ArrayList<>();
        long[] window=new long[2];
        for(int i=0;i<concurrency;i++) {
            Worker w=new Worker();workers.add(w);
            w.thread=new Thread(()->{
                ready.countDown();
                try { go.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt();return; }
                while(!stop.get() && (once || System.nanoTime()<window[1])) {
                    BenchmarkMessage request=Workload.request(config.runId(),phase,sequence.getAndIncrement(),payload,checksum);
                    w.attempted++;
                    long start=System.nanoTime();w.activeStart=start;
                    try {
                        BenchmarkMessage response=rpc.call(request);Workload.validate(request,response);
                        long end=System.nanoTime();w.activeStart=0;
                        long latency=end-start;
                        if(w.timedOut || latency>TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis())) {
                            w.timedOut=true;w.timeouts++;stop.set(true);
                        } else if(once || end<=window[1]) {
                            w.completed++;w.maxLatency=Math.max(w.maxLatency,latency);w.latency.recordValue(latency);
                        } else { w.drainCompleted++;w.maxDrainLatency=Math.max(w.maxDrainLatency,latency);w.drainLatency.recordValue(latency); }
                    } catch(Throwable error) {
                        w.activeStart=0;
                        if(w.timedOut || isTimeout(error)) { w.timeouts++; }
                        else if(error instanceof Workload.InvalidResponseException) w.invalidResponses++;
                        else w.errors++;
                        String message=error.getClass().getName()+": "+Objects.toString(error.getMessage(),"");
                        w.firstError=message.substring(0,Math.min(2000,message.length()));
                        stop.set(true);
                    } finally { w.activeStart=0; }
                    if(once) break;
                }
            },"benchmark-worker-"+i);
            w.thread.setDaemon(true);w.thread.start();
        }
        if(!ready.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not start");
        long startEpochMillis=System.currentTimeMillis();
        long cpuBefore=cpu(),start=System.nanoTime();window[0]=start;window[1]=start+(long)(seconds*1e9);
        go.countDown();long lastSnapshot=start,nextSnapshot=start+TimeUnit.SECONDS.toNanos(1);
        Histogram histogram=new Histogram(TimeUnit.MINUTES.toNanos(2),3),drainHistogram=new Histogram(TimeUnit.MINUTES.toNanos(2),3);
        boolean aborted=false;long abortDeadline=Long.MAX_VALUE;long deadline=(once?start+TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis()):window[1])+TimeUnit.MILLISECONDS.toNanos(config.drainMillis());
        while(workers.stream().anyMatch(w->w.thread.isAlive())) {
            long now=System.nanoTime();
            for(Worker w:workers) {
                long active=w.activeStart;
                if(active!=0 && now-active>=TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis()) && !w.timedOut) {
                    w.timedOut=true;stop.set(true);w.thread.interrupt();
                }
            }
            if(stop.get() && abortDeadline==Long.MAX_VALUE) abortDeadline=now+TimeUnit.MILLISECONDS.toNanos(config.drainMillis());
            if(now>=nextSnapshot) {
                snapshot(phase,workers,histogram,intervals,start,lastSnapshot,now);lastSnapshot=now;nextSnapshot=now+TimeUnit.SECONDS.toNanos(1);
            }
            if(now>=Math.min(deadline,abortDeadline)) { aborted=true;stop.set(true);workers.forEach(w->w.thread.interrupt());break; }
            Thread.sleep(5);
        }
        long finish=System.nanoTime();
        snapshot(phase,workers,histogram,intervals,start,lastSnapshot,finish);
        long attempted=0,completed=0,drained=0,timeouts=0,errors=0,invalid=0,unresolved=0;
        List<String> firstErrors=new ArrayList<>();
        for(Worker w:workers) {
            attempted+=w.attempted;completed+=w.completed;drained+=w.drainCompleted;timeouts+=w.timeouts;errors+=w.errors;invalid+=w.invalidResponses;
            if(w.thread.isAlive()) { unresolved++;if(w.timedOut) timeouts++; }
            drainHistogram.add(w.drainLatency.getIntervalHistogram());
            if(w.firstError!=null)firstErrors.add(w.firstError);
        }
        double windowSeconds=once?Math.max(1e-9,(finish-start)/1e9):seconds;
        boolean valid=!aborted && !stop.get() && completed+drained>0 && attempted==completed+drained && histogram.getTotalCount()==completed;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("startEpochMillis",startEpochMillis);result.put("endEpochMillis",startEpochMillis+(long)(windowSeconds*1000));
        result.put("valid",valid);result.put("attempted",attempted);result.put("completed",completed);result.put("drainCompleted",drained);
        result.put("timeouts",timeouts);result.put("errors",errors);result.put("invalidResponses",invalid);result.put("unresolved",unresolved);
        result.put("histogramOverflowOrRecordingError",completed!=histogram.getTotalCount());
        result.put("windowSeconds",windowSeconds);result.put("completedRpcsPerSecond",completed/windowSeconds);
        result.put("elapsedIncludingDrainSeconds",(finish-start)/1e9);result.put("latencyNanos",quantiles(histogram,workers.stream().mapToLong(w->w.maxLatency).max().orElse(0)));result.put("drainLatencyNanos",quantiles(drainHistogram,workers.stream().mapToLong(w->w.maxDrainLatency).max().orElse(0)));
        result.put("firstErrors",firstErrors);result.put("clientCpuCoresIncludingDrain",(cpu()-cpuBefore)/(double)Math.max(1,finish-start));
        HistogramLogWriter writer=new HistogramLogWriter(config.output().resolve(phase+".hlog").toFile());
        try {
            writer.outputLogFormatVersion();writer.outputIntervalHistogram(histogram);
        } finally { writer.close(); }
        return new PhaseResult(valid,result);
    }
    private static boolean isTimeout(Throwable t) {
        for(Throwable e=t;e!=null;e=e.getCause()) {
            String name=e.getClass().getName(),message=Objects.toString(e.getMessage(),"");
            if(e instanceof TimeoutException || name.contains("Timeout") || message.contains("DEADLINE_EXCEEDED"))return true;
        }
        return false;
    }
    private static long cpu() { return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime(); }
    private static Map<String,Long> quantiles(Histogram h,long exactMax) {
        return Map.of("count",h.getTotalCount(),"p50",Math.min(exactMax,h.getValueAtPercentile(50)),"p95",Math.min(exactMax,h.getValueAtPercentile(95)),"p99",Math.min(exactMax,h.getValueAtPercentile(99)),"max",exactMax);
    }
    private static void snapshot(String phase,List<Worker> workers,Histogram total,PrintWriter out,long start,long previous,long now) {
        Histogram interval=new Histogram(TimeUnit.MINUTES.toNanos(2),3);
        for(Worker w:workers)interval.add(w.latency.getIntervalHistogram());total.add(interval);
        out.printf(Locale.ROOT,"%s,%.9f,%.9f,%d,%.3f,%d,%d,%d,%d%n",phase,(now-start)/1e9,(now-previous)/1e9,interval.getTotalCount(),
            interval.getTotalCount()*1e9/Math.max(1,now-previous),interval.getValueAtPercentile(50),interval.getValueAtPercentile(95),interval.getValueAtPercentile(99),interval.getMaxValue());out.flush();
    }
    private static final class Worker {
        Thread thread;volatile long activeStart;volatile boolean timedOut;
        volatile long attempted,completed,drainCompleted,timeouts,errors,invalidResponses,maxLatency,maxDrainLatency;
        String firstError;
        final Recorder latency=new Recorder(TimeUnit.MINUTES.toNanos(2),3),drainLatency=new Recorder(TimeUnit.MINUTES.toNanos(2),3);
    }
}
