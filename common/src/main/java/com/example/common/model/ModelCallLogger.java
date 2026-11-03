package com.example.common.model;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ModelCallLogger {

    private final JdbcClient jdbc;

    public ModelCallLogger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void createModelCall(ModelCall modelCall,String provider, String model,String status,Usage usage, int latency){
        jdbc.sql("""
        insert into model_calls (tenant_id,purpose,provider,model,status,input_tokens,output_tokens,cached_tokens,cost_usd,latency_ms)
        values (:tenantId,:purpose,:provider,:model,:status,:inputTokens,:outputTokens,:cachedTokens,:costUsd,:latencyMs)
        """)
                .param("tenantId", modelCall.tenantId())
                .param("purpose", modelCall.purpose())
                .param("provider", provider)
                .param("model", model)
                .param("status", status)
                .param("inputTokens", usage.inputTokens())
                .param("outputTokens", usage.outputTokens())
                .param("cachedTokens", usage.cachedTokens())
                .param("costUsd", usage.costUsd())
                .param("latencyMs", latency)
                .update();
    }
}
