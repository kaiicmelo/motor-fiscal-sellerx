package com.sellerx.motorfiscal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fincatto.documentofiscal.nfe400.webservices.WSFacade;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.DFAmbiente;
import com.fincatto.documentofiscal.DFUnidadeFederativa;
import com.fincatto.documentofiscal.DFModelo;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyStore;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.fincatto.documentofiscal.nfe400.classes.nota.*;
import com.fincatto.documentofiscal.nfe400.classes.CRT;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteEnvio;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteEnvioRetorno;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteEnvioRetornoDados;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteIndicadorProcessamento;
import com.fincatto.documentofiscal.nfe400.classes.statusservico.consulta.NFStatusServicoConsultaRetorno;
import java.time.ZonedDateTime;
import java.util.Random;

@RestController
@RequestMapping("/api/nfe")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class NfeController {

    private final ExecutorService filaDeProcessamento = Executors.newSingleThreadExecutor();

    @RequestMapping(value = "/process", method = {RequestMethod.POST, RequestMethod.GET, RequestMethod.OPTIONS})
    public CompletableFuture<ResponseEntity<Map<String, Object>>> processAction(@RequestBody(required = false) Map<String, Object> payload) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (payload == null || payload.isEmpty()) {
                    return ResponseEntity.ok(Map.of("status", "online", "message", "Motor Fiscal rodando e mapeado corretamente na rota /api/nfe/process"));
                }

                String action = (String) payload.get("action");
                
                if ("ping_test".equals(action)) {
                    return ResponseEntity.ok(Map.of("status", "online", "message", "Ping test concluído. A rota existe e responde perfeitamente."));
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> company = (Map<String, Object>) payload.get("company");
                
                if (action == null || company == null || company.isEmpty()) {
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
                
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(new ByteArrayInputStream(pfxBytes), certPass.toCharArray());

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
                    public KeyStore getCertificadoKeyStore() {
                        return ks;
                    }
                    @Override
                    public String getCertificadoSenha() {
                        return certPass;
                    }
                    @Override
                    public KeyStore getCadeiaCertificadosKeyStore() {
                        return ks; 
                    }
                    @Override
                    public String getCadeiaCertificadosSenha() {
                        return certPass;
                    }
                };

                Map<String, Object> result = new HashMap<>();
                WSFacade wsFacade = new WSFacade(config); 

                switch (action) {
                    case "emitir":
                        result = handleEmitir(payload, wsFacade, config);
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
        }, filaDeProcessamento);
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
    private Map<String, Object> handleEmitir(Map<String, Object> payload, WSFacade wsFacade, NFeConfig config) throws Exception {
        NFNota nota = new NFNota();
        NFNotaInfo info = new NFNotaInfo();
        
        // --- Bloco IDE (Identificação) ---
        NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
        ide.setUf(config.getCUF());
        ide.setAmbiente(config.getAmbiente());
        ide.setModelo(DFModelo.NFE);
        ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
        
        Map<String, Object> invoiceData = payload.containsKey("invoice") ? (Map<String, Object>) payload.get("invoice") : new HashMap<>();
        
        ide.setNaturezaOperacao((String) invoiceData.getOrDefault("natureza_operacao", "VENDA DE MERCADORIA"));
        ide.setSerie(invoiceData.containsKey("serie") ? invoiceData.get("serie").toString() : "1");
        ide.setNumeroNota(invoiceData.containsKey("numero") ? invoiceData.get("numero").toString() : "1");
        ide.setDataHoraEmissao(ZonedDateTime.now());
        info.setIdentificacao(ide);
        
        // --- Bloco Emitente (Remetente) ---
        Map<String, Object> companyData = payload.containsKey("company") ? (Map<String, Object>) payload.get("company") : new HashMap<>();
        
        NFNotaInfoEmitente emitente = new NFNotaInfoEmitente();
        emitente.setCnpj((String) companyData.getOrDefault("cnpj", "00000000000000"));
        emitente.setRazaoSocial((String) companyData.getOrDefault("razao_social", "EMPRESA TESTE LTDA"));
        emitente.setNomeFantasia((String) companyData.getOrDefault("nome_fantasia", "EMPRESA TESTE"));
        emitente.setInscricaoEstadual((String) companyData.getOrDefault("inscricao_estadual", "ISENTO"));
        emitente.setCrt(CRT.SIMPLES_NACIONAL);
        
        NFNotaInfoEndereco enderecoEmitente = new NFNotaInfoEndereco();
        enderecoEmitente.setLogradouro((String) companyData.getOrDefault("logradouro", "RUA DE TESTE"));
        enderecoEmitente.setNumero((String) companyData.getOrDefault("numero", "123"));
        enderecoEmitente.setBairro((String) companyData.getOrDefault("bairro", "CENTRO"));
        enderecoEmitente.setCodigoMunicipio((String) companyData.getOrDefault("codigo_municipio", "3516200")); // Código IBGE Franca/SP
        enderecoEmitente.setDescricaoMunicipio((String) companyData.getOrDefault("municipio", "Franca"));
        enderecoEmitente.setUf(config.getCUF());
        enderecoEmitente.setCep((String) companyData.getOrDefault("cep", "14400000"));
        
        emitente.setEndereco(enderecoEmitente);
        info.setEmitente(emitente);
        // ----------------------------------
        
        nota.setInfo(info);
        
        NFLoteEnvio lote = new NFLoteEnvio();
        lote.setNotas(Collections.singletonList(nota));
        lote.setIdLote("1");
        lote.setVersao("4.00");
        lote.setIndicadorProcessamento(NFLoteIndicadorProcessamento.PROCESSAMENTO_ASSINCRONO);
        
        NFLoteEnvioRetornoDados dados = wsFacade.enviaLote(lote);
        NFLoteEnvioRetorno retorno = dados.getRetorno();
        
        return Map.of(
            "status", retorno.getStatus() != null ? retorno.getStatus() : "erro", 
            "motivo", retorno.getMotivo() != null ? retorno.getMotivo() : "Motivo não retornado pela Sefaz"
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleCancelar(Map<String, Object> payload, WSFacade wsFacade) throws Exception {
        return Map.of("status", "erro", "mensagem", "Não implementado neste mockup");
    }

    private Map<String, Object> handleConsultarStatus(Map<String, Object> payload, WSFacade wsFacade, DFUnidadeFederativa uf) throws Exception {
        NFStatusServicoConsultaRetorno retorno = wsFacade.consultaStatus(uf, DFModelo.NFE);
        return Map.of("status", retorno.getStatus() != null ? retorno.getStatus() : "erro", "motivo", retorno.getMotivo());
    }
}

// Sync: 2026-04-23T19:30:23.143Z