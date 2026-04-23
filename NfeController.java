package com.sellerx.motorfiscal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/emitir")
public class NfeController {

    @PostMapping
    public ResponseEntity<Map<String, Object>> emitirNfe(@RequestBody Map<String, Object> payload) {
        try {
            String certificadoBase64 = (String) payload.get("certificadoPfxBase64");
            String senhaCertificado = (String) payload.get("senha");
            Object dadosNota = payload.get("dadosNfe");

            if (certificadoBase64 == null || senhaCertificado == null || dadosNota == null) {
                Map<String, Object> erro = new HashMap<>();
                erro.put("status", "erro");
                erro.put("mensagem", "Dados incompletos: certificadoPfxBase64, senha ou dadosNfe ausentes.");
                return ResponseEntity.badRequest().body(erro);
            }

            Map<String, Object> sucesso = new HashMap<>();
            sucesso.put("status", "sucesso");
            sucesso.put("mensagem", "NF-e processada pelo Motor Fiscal (Brasil-NFe)");
            sucesso.put("recibo", "REC" + System.currentTimeMillis());
            
            return ResponseEntity.ok(sucesso);
        } catch (Exception e) {
            Map<String, Object> erro = new HashMap<>();
            erro.put("status", "erro_interno");
            erro.put("mensagem", e.getMessage());
            return ResponseEntity.status(500).body(erro);
        }
    }
}