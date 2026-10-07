package com.wine.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wine.cellar.service.SaleOutService;
import com.wine.cellar.service.WineService;

/**
 * 停售/复售 HTTP 端到端：状态码、{ok,message,code,action} 透传、资格字段序列化、
 * PUT 改状态被拒、动作接口幂等保护。使用随机端口真实起服（H2 测试库）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WineSellingControllerTest {

    @Autowired TestRestTemplate rest;
    @Autowired WineService wineService;
    @Autowired SaleOutService saleService;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void listReturnsQualificationFields() throws Exception {
        ResponseEntity<String> resp = rest.getForEntity("/api/wines", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode body = json.readTree(resp.getBody());
        assertTrue(body.path("ok").asBoolean());
        JsonNode wines = body.path("data");
        // 每款酒都必须下发统一资格口径字段，且 saleEligible 与「在售 && 可售量>0」自洽
        for (JsonNode w : wines) {
            assertNotNull(w.get("sellableBatchStock"), "必须下发可售批次合计");
            assertNotNull(w.get("saleEligible"), "必须下发可销售选择资格");
            boolean expect = "在售".equals(w.path("status").asText())
                    && w.path("sellableBatchStock").asInt() > 0;
            assertEquals(expect, w.path("saleEligible").asBoolean());
        }
        // 酒款1（种子在售、两可售批次合计 80）可销售；酒款3（停售）不可销售
        JsonNode w1 = wines.get(0);
        assertEquals(80, w1.path("sellableBatchStock").asInt());
        assertTrue(w1.path("saleEligible").asBoolean());
    }

    @Test
    void stopSellingOk_thenResumeSellingOk_roundTrip() throws Exception {
        // 酒款2 无在途：停售成功
        ResponseEntity<String> stop = rest.postForEntity("/api/wines/2/stop-selling", null, String.class);
        assertEquals(HttpStatus.OK, stop.getStatusCode());
        JsonNode stopBody = json.readTree(stop.getBody());
        assertTrue(stopBody.path("ok").asBoolean());
        assertEquals("停售", stopBody.path("data").path("status").asText());
        assertFalse(stopBody.path("data").path("saleEligible").asBoolean());

        // 重复停售：400 + 明确提示
        ResponseEntity<String> dup = rest.postForEntity("/api/wines/2/stop-selling", null, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, dup.getStatusCode());
        JsonNode dupBody = json.readTree(dup.getBody());
        assertFalse(dupBody.path("ok").asBoolean());
        assertTrue(dupBody.path("message").asText().contains("已停售"));

        // 酒款2 无可用可售批次（只有窖藏中），复售应被资格复核拒绝：400 + code/action
        ResponseEntity<String> resumeBad = rest.postForEntity("/api/wines/2/resume-selling", null, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resumeBad.getStatusCode());
        JsonNode rb = json.readTree(resumeBad.getBody());
        assertEquals("WINE_RESALE_NOT_QUALIFIED", rb.path("code").asText());
        assertEquals("cellar-batches", rb.path("action").asText());
        assertEquals("停售", wineService.get(2L).status);   // 原停售状态不变
    }

    @Test
    void stopSellingBlockedReturnsReasonAndEntry() throws Exception {
        // 先出窖 3 瓶酒款1，制造在途单
        SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
        SaleOutService.SaleItemInput it = new SaleOutService.SaleItemInput();
        it.wineId = 1L; it.qty = 3;
        in.items = java.util.List.of(it);
        saleService.create(in);

        ResponseEntity<String> resp = rest.postForEntity("/api/wines/1/stop-selling", null, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        JsonNode body = json.readTree(resp.getBody());
        assertFalse(body.path("ok").asBoolean());
        assertEquals("WINE_ACTIVE_SALE_BLOCKED", body.path("code").asText());
        assertEquals("sale-outs", body.path("action").asText());
        assertTrue(body.path("message").asText().contains("退窖"));
        // 状态保持在售
        assertEquals("在售", wineService.get(1L).status);
    }

    @Test
    void putCannotChangeStatus() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Content-Type", "application/json");
        org.springframework.http.HttpEntity<String> req =
                new org.springframework.http.HttpEntity<>("{\"status\":\"停售\"}", headers);

        @SuppressWarnings("rawtypes")
        ResponseEntity<java.util.Map> resp = rest.exchange(
                "/api/wines/1", org.springframework.http.HttpMethod.PUT, req, java.util.Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("在售", wineService.get(1L).status);
    }
}
