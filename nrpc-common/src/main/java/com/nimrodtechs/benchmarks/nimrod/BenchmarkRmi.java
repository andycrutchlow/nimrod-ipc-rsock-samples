package com.nimrodtechs.benchmarks.nimrod;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmiInterface;
import com.nimrodtechs.ipcrsock.annotations.SchedulerType;
import com.nimrodtechs.samples.benchmark.common.BenchmarkMessage;

@NimrodRmiInterface(serviceName="benchmark",concurrency=8,scheduler=SchedulerType.PARALLEL)
public interface BenchmarkRmi {
    BenchmarkMessage benchmarkEcho(BenchmarkMessage request) throws Exception;
}
