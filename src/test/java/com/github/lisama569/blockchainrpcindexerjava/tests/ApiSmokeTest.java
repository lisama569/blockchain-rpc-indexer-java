package com.github.lisama569.blockchainrpcindexerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiSmokeTest {

    @Autowired
    MockMvc mvc;

    @Test
    void stepIndex_queryAndRejectRollback() throws Exception {
        mvc.perform(post("/api/index/step").param("batches", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("OK"))
                .andExpect(jsonPath("$.position").value(2));

        mvc.perform(get("/api/blocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(get("/api/accounts")).andExpect(status().isOk());
        mvc.perform(get("/api/reorgs")).andExpect(status().isOk());
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.nodeHeads.length()").value(2));

        // 位点回退必须被明确拒绝
        mvc.perform(post("/api/position/reset").param("height", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failureKind").value("POSITION_ROLLBACK_REJECTED"));
    }
}
