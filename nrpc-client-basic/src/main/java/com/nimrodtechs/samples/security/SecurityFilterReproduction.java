package com.nimrodtechs.samples.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimrodtechs.ipcrsock.client.RemoteServerService;
import com.nimrodtechs.samples.dto.SecurityFilterRecord;
import com.nimrodtechs.samples.rmiservices.security.SecurityFiltersRmi;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@Configuration
@Profile("security-filter-repro")
public class SecurityFilterReproduction {
    @FunctionalInterface private interface Call { Object invoke() throws Exception; }
    private record Invocation(Object response, long roundTripNanos) {}

    @Bean ApplicationRunner reproduceSecurityFilters(SecurityFiltersRmi service, RemoteServerService remote, Environment env) {
        return args -> {
            List<Map<String, Object>> results = new ArrayList<>();
            List<Integer> counts = Arrays.stream(env.getProperty("repro.counts", "1,100").split(","))
                    .map(Integer::parseInt).toList();
            run(results, "record", 1, () -> service.getSecurityFilterRecord(0));
            run(results, "array", 1, () -> service.getSecurityFilterArray(1));
            for (int count : counts) run(results, "list", count, () -> service.getSecurityFilters(count));
            if (env.getProperty("repro.include-type-probe", Boolean.class, false))
                run(results, "list-as-object-array-probe", 1,
                        () -> remote.executeRmiMethod(Object[].class, "server1", "getSecurityFilters", 1));
            Path output = Path.of(env.getProperty("repro.output", "benchmark-results/security-filter-repro/client-results.json"));
            Files.createDirectories(output.toAbsolutePath().getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);
            long hold = env.getProperty("repro.debug-hold-millis", Long.class, 0L);
            if (hold < 0 || hold > 60000) throw new IllegalArgumentException("debug hold must be between 0 and 60000 ms");
            if (hold > 0) Thread.sleep(hold);
            System.exit(results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("success"))) ? 0 : 2);
        };
    }

    private void run(List<Map<String, Object>> results, String type, int count, Call call) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("responseType", type); result.put("requestedCount", count);
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "security-filter-rpc"); thread.setDaemon(true); return thread;
        });
        Future<Invocation> pending = worker.submit(() -> {
            long started = System.nanoTime();
            Object response = call.invoke();
            return new Invocation(response, System.nanoTime() - started);
        });
        try {
            Invocation invocation = pending.get(30, TimeUnit.SECONDS);
            Object response = invocation.response();
            long roundTripNanos = invocation.roundTripNanos();
            List<?> records = response instanceof List<?> list ? list
                    : response instanceof Object[] array ? Arrays.asList(array) : Collections.singletonList(response);
            if (records.size() != count) throw new AssertionError("Wrong record count: " + records.size());
            for (int i = 0; i < count; i++) {
                SecurityFilterRecord expected = new SecurityFilterRecord("dv-" + i, "cusip-" + i, "isin-" + i,
                        "figi-" + i, "universe-" + i, "industry-" + i, "security-type-" + i, "market-sector-" + i);
                if (!expected.equals(records.get(i))) throw new AssertionError("Record mismatch at index " + i);
            }
            result.put("success", true); result.put("receivedCount", records.size());
            result.put("runtimeType", response.getClass().getName()); result.put("roundTripNanos", roundTripNanos);
        } catch (Throwable error) {
            pending.cancel(true);
            Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
            result.put("success", false); result.put("error", cause.toString());
            cause.printStackTrace();
        } finally {
            worker.shutdownNow(); results.add(result);
            System.out.println("SECURITY_FILTER_RESULT " + result);
        }
    }
}
