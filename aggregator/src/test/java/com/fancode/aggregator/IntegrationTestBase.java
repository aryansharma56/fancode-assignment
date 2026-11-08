package com.fancode.aggregator;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * One Postgres container and one WireMock server for the whole test run (singleton pattern),
 * so every test class shares one Spring context. Data tables are cleared before each test;
 * the seeded match and provider mapping are kept.
 */
@SpringBootTest(properties = {
        "aggregator.provider-b.enabled=false",
        "aggregator.primary-provider=provider-a"
})
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final WireMockServer PROVIDER_B = new WireMockServer(options().dynamicPort());

    static {
        POSTGRES.start();
        PROVIDER_B.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("aggregator.provider-b.base-url", PROVIDER_B::baseUrl);
    }

    protected static final String MATCH = "match-1001";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE commentary, provider_snapshots, processed_deliveries, provider_health");
        PROVIDER_B.resetAll();
    }

    protected ResultActions deliver(String json) throws Exception {
        return mvc.perform(post("/webhooks/provider-a").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions getJson(String path) throws Exception {
        return mvc.perform(get(path).accept(MediaType.APPLICATION_JSON));
    }

    protected int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
