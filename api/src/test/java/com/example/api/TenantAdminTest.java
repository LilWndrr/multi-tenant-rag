package com.example.api;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "rag.admin.token=test-token")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public class TenantAdminTest {

    @Autowired
    MockMvc mvc;

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mvc.perform(get("/admin/tenants"))
                .andExpect(status().isUnauthorized());
    }



    @Test
    void createsAndListsTenant() throws Exception {
        mvc.perform(post("/admin/tenants")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"name": "acme", "languageProfile": "tr"}
                    """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("acme"));

        mvc.perform(get("/admin/tenants").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'acme')]").exists());
    }
    @Test
    void languageProfileIsNotTrOrEn() throws Exception {
        mvc.perform(post("/admin/tenants").header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "acme", "languageProfile": "de"}
                    """)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateName() throws Exception {
        String body = """
        {"name": "dup-test", "languageProfile": "en"}
        """;

        mvc.perform(post("/admin/tenants")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/admin/tenants")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }
}
