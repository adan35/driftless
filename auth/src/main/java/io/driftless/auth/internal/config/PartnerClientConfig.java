package io.driftless.auth.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} the saga calls the partner-simulator with, under a <strong>hard,
 * bounded timeout</strong> sourced from {@link AuthProperties.Partner}.
 *
 * <p>The connect and read timeouts are the crux of the "$20K drift fix": a read-timeout breach
 * surfaces as a {@code ResourceAccessException}, which the partner client maps to a "no response"
 * outcome that drives the compensating reversal. The base URL points at the partner by service name
 * in the monolith deployment.
 */
@Configuration
public class PartnerClientConfig {

    /** Qualifier-free single {@link RestClient}; the partner client is its only consumer. */
    @Bean
    public RestClient partnerRestClient(AuthProperties properties) {
        AuthProperties.Partner partner = properties.getPartner();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) partner.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) partner.getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(partner.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
