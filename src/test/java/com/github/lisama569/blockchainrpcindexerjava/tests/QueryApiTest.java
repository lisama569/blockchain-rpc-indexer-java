package com.github.lisama569.blockchainrpcindexerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 查询接口冒烟：接口可启动并返回规范链视图（空库）。 */
@SpringBootTest
@AutoConfigureMockMvc
class QueryApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void statusEndpointReportsEmptyCanonicalChain() throws Exception {
        mvc.perform(get("/api/indexer/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoint").value(-1))
                .andExpect(jsonPath("$.txCount").value(0));
        mvc.perform(get("/api/indexer/transactions").param("from", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/api/indexer/balances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoint").value(-1));
    }
}
