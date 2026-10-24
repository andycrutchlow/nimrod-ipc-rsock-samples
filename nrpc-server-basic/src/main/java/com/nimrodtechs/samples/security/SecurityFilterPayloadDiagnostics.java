package com.nimrodtechs.samples.security;

import io.rsocket.Payload;
import io.rsocket.core.RSocketServer;
import io.rsocket.util.RSocketProxy;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicInteger;

/** Observes final RSocket data length; the ordinary Nimrod encoder/decoder remain unchanged. */
@Configuration
@Profile("security-filter-repro")
public class SecurityFilterPayloadDiagnostics {
    @Bean static BeanPostProcessor securityFilterPayloadObserver() {
        AtomicInteger calls = new AtomicInteger();
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof RSocketServer server && "nimrodRSocketServer".equals(name)) {
                    server.interceptors(registry -> registry.forResponder((io.rsocket.plugins.RSocketInterceptor) socket -> new RSocketProxy(socket) {
                        @Override public Mono<Payload> requestResponse(Payload request) {
                            int call = calls.incrementAndGet();
                            return super.requestResponse(request).doOnNext(response ->
                                    System.out.println("SECURITY_FILTER_PAYLOAD call=" + call
                                            + " serializedBytes=" + response.data().readableBytes()));
                        }
                    }));
                }
                return bean;
            }
        };
    }
}
