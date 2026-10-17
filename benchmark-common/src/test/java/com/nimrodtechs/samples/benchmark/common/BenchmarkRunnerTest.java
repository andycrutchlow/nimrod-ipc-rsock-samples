package com.nimrodtechs.samples.benchmark.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkRunnerTest {
    @TempDir Path output;
    private BenchmarkConfig config(int concurrency,int timeout) { return new BenchmarkConfig("test",128,concurrency,.03,.05,timeout,100,42,output); }
    @SuppressWarnings("unchecked")
    @Test void concurrencyAndCountsAreBoundedAndDrainReconciles() throws Exception {
        AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();
        var result=new BenchmarkRunner(config(8,1000),request->{
            int count=active.incrementAndGet();max.accumulateAndGet(count,Math::max);
            try {Thread.sleep(1);return Workload.process(request);}finally {active.decrementAndGet();}
        },Map.of("library","fake")).run();
        assertEquals(true,result.get("valid"));assertTrue(max.get()<=8);
        Map<String,Object> measured=(Map<String,Object>)result.get("measurement");
        assertEquals(((Number)measured.get("attempted")).longValue(),((Number)measured.get("completed")).longValue()+((Number)measured.get("drainCompleted")).longValue());
        var latency=(Map<String,Object>)measured.get("latencyNanos");assertEquals(measured.get("completed"),latency.get("count"));
        assertTrue(((Number)latency.get("p99")).longValue()<=((Number)latency.get("max")).longValue());
    }
    @SuppressWarnings("unchecked")
    @Test void interruptibleStallTimesOutWithoutReplacementOrHang() {
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->{
            AtomicInteger calls=new AtomicInteger();
            var result=new BenchmarkRunner(config(1,50),request->{calls.incrementAndGet();Thread.sleep(10000);return Workload.process(request);},Map.of()).run();
            assertEquals(false,result.get("valid"));assertEquals(1,calls.get());
            Map<String,Object> ready=(Map<String,Object>)result.get("readiness");assertEquals(1L,ready.get("timeouts"));
        });
    }
    @SuppressWarnings("unchecked")
    @Test void uninterruptibleCallIsReportedUnresolvedAndDoesNotHangSupervisor() {
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->{
            java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
            AtomicInteger calls=new AtomicInteger();
            try {
                var result=new BenchmarkRunner(config(1,50),request->{
                    calls.incrementAndGet();
                    while(release.getCount()>0) { try {release.await();}catch(InterruptedException ignored) { } }
                    return Workload.process(request);
                },Map.of()).run();
                assertEquals(false,result.get("valid"));assertEquals(1,calls.get());
                Map<String,Object> ready=(Map<String,Object>)result.get("readiness");assertEquals(1L,ready.get("unresolved"));
            } finally { release.countDown(); }
        });
    }
    @Test void invalidRepliesInvalidateRun() throws Exception {
        var result=new BenchmarkRunner(config(1,1000),request->{var response=Workload.process(request);response.setPrice("wrong");return response;},Map.of()).run();
        assertEquals(false,result.get("valid"));assertNull(result.get("measurement"));
    }
    @Test void invalidConfigurationRejectedBeforeCalls() {
        assertThrows(IllegalArgumentException.class,()->new BenchmarkConfig("test",127,1,30,120,5000,30000,42,output));
        assertThrows(IllegalArgumentException.class,()->new BenchmarkConfig("test",128,33,30,120,5000,30000,42,output));
    }
}
