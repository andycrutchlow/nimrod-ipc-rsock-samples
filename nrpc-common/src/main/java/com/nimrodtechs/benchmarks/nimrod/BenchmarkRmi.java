package com.nimrodtechs.benchmarks.nimrod;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmiInterface;
import com.nimrodtechs.samples.benchmark.common.BenchmarkMessage;

@NimrodRmiInterface(serviceName = "benchmark")
public interface BenchmarkRmi {
    BenchmarkMessage benchmarkEcho(BenchmarkMessage request) throws Exception;
}