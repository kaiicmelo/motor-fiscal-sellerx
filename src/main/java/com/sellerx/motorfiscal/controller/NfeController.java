package com.sellerx.motorfiscal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fincatto.documentofiscal.nfe.WSFacade;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.nfe.classes.nota.NFNota;
import com.fincatto.documentofiscal.nfe.classes.lote.envio.NFLoteEnvioRetorno;
import com.fincatto.documentofiscal.nfe.classes.evento.NFEnviaEventoRetorno;
import com.fincatto.documentofiscal.nfe.classes.statusservico.consulta.NFStatusServicoConsultaRetorno;
import com.fincatto.documentofiscal.nfe.classes.cadastro.NFRetornoConsultaCadastro;
import com.fincatto.documentofiscal.nfe.classes.nota.consulta.NFNotaConsultaRetorno;
import com.fincatto.documentofiscal.DFAmbiente;
import com.fincatto.documentofiscal.DFUnidadeFederativa;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyStore;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import com.fincatto.documentofiscal.nfe.classes.nota.*;
import java.math.BigDecimal;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/nfe")
public class NfeController {

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processAction(@RequestBody Map<String, Object> payload) {
        try {
            String action = (String) payload.get("action");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> company = (Map<String, Object>) payload.get("company");
            
            if (action == null || company == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Atributos 'action' ou 'company' ausentes no payload."));
            }

            String certUri = (String) company.get("certificate_file_uri");
            String certPass = (String) company.get("certificate_password");
            String ufString = (String) company.get("uf");
            String ambienteStr = (String) company.getOrDefault("ambiente", "HOMOLOGACAO");

            if (certUri == null || certPass == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Credenciais do certificado ausentes."));
            }

            byte[] pfxBytes = downloadCertificado(certUri);
            
            NFeConfig config = new NFeConfig() {
                @Override
                public DFUnidadeFederativa getCUF() {
                    return ufString != null ? DFUnidadeFederativa.valueOfCodigo(ufString) : DFUnidadeFederativa.SP;
                }
                @Override
                public DFAmbiente getAmbiente() {
                    return "PRODUCAO".equalsIgnoreCase(ambienteStr) ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO;
                }
                @Override
                public KeyStore getCertificadoKeyStore() throws Exception {
                    KeyStore ks = KeyStore.getInstance("PKCS12");
                    ks.load(new ByteArrayInputStream(pfxBytes), getCertificadoSenha().toCharArray());
                    return ks;
                }
                @Override
                public String getCertificadoSenha() {
                    return certPass;
                }
                @Override
                public KeyStore getCadeiaCertificadosKeyStore() throws Exception {
                    return KeyStore.getInstance("JKS"); 
                }
                @Override
                public String getCadeiaCertificadosSenha() {
                    return "changeit";
                }
            };

            Map<String, Object> result = new HashMap<>();
            WSFacade wsFacade = new WSFacade(config);
            
            switch (action) {
                case "emitir":
                    result = handleEmitir(payload, wsFacade);
                    break;
                case "cancelar":
                    result = handleCancelar(payload, wsFacade);
                    break;
                case "consultar_status":
                    result = handleConsultarStatus(payload, wsFacade, config.getCUF());
                    break;
                default:
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Ação não suportada pelo Motor: " + action));
            }
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", "Erro interno no Motor Fiscal: " + e.getMessage()));
        }
    }

    private byte[] downloadCertificado(String uriString) throws Exception {
        URL url = new URL(uriString);
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleEmitir(Map<String, Object> payload, WSFacade wsFacade) throws Exception {
        NFNota nota = new NFNota();
        NFNotaInfo info = new NFNotaInfo();
        nota.setInfo(info);
        // Implementação resumida para deploy automático
        NFLoteEnvioRetorno retorno = wsFacade.enviaLote(nota);
        return Map.of("status", retorno.getStatus() != null ? retorno.getStatus() : "erro");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleCancelar(Map<String, Object> payload, WSFacade wsFacade) throws Exception {
        return Map.of("status", "erro", "mensagem", "Não implementado neste mockup");
    }

    private Map<String, Object> handleConsultarStatus(Map<String, Object> payload, WSFacade wsFacade, DFUnidadeFederativa uf) throws Exception {
        NFStatusServicoConsultaRetorno retorno = wsFacade.consultaStatus(uf);
        return Map.of("status", retorno.getStatus() != null ? retorno.getStatus() : "erro");
    }
}