package com.example.demo;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@SpringBootApplication
@RestController
public class DemoApplication {

	public static void main(String[] args) {
		Schedulers.enableMetrics();
		SpringApplication.run(DemoApplication.class, args);
	}

	final Sinks.Many<String> latestChange = Sinks.many().replay().latest();
	final FluxProcessor<String, String> processor = FluxProcessor.fromSink(latestChange);

	public DemoApplication(WebClient.Builder webClientBuilder) {
		WebClient webClient = webClientBuilder.build();

		webClient.get()
				.uri("https://stream.wikimedia.org/v2/stream/recentchange")
				.retrieve()
				.bodyToFlux(JsonNode.class)
				.name("recentChanges")
				.metrics()
				.concatMap(change -> {
					return processChange(change)
							.name("processing")
							.metrics()
							.onErrorResume(IllegalStateException.class, __ -> Mono.empty());
				})
				.doOnNext(processor::onNext)
				// Avoid polluting the logs
				.sample(Duration.ofSeconds(1))
				.log()
				.retryWhen(Retry.fixedDelay(100, Duration.ofSeconds(1)))
				.subscribe();
	}

	@GetMapping("/latestChange")
	public Mono<String> latestChange() {
		return processor.next();
	}

	Mono<String> processChange(JsonNode change) {
		if (change.path("bot").asBoolean()) {
			return Mono.error(new IllegalStateException("OMG! I don't know how to handle the bots!"));
		}
		return Mono.just("Change to '" + change.path("title").asText(null) + "' by '" + change.path("user").asText(null) + "'");
	}

}
