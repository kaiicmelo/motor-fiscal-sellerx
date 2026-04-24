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
import java.time.ZonedDateTime;
import java.util.Random;

import com.fincatto.documentofiscal.nfe400.classes.nota.*;
import com.fincatto.documentofiscal.nfe400.classes.*;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.*;
import com.fincatto.documentofiscal.nfe400.classes.statusservico.consulta.NFStatusServicoConsultaRetorno;

@RestController
@RequestMapping("/api/nfe")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class NfeController {

    private final ExecutorService filaDeProcessamento = Executors.newSingleThreadExecutor();

    @RequestMapping(value = "/process", method = {RequestMethod.POST, RequestMethod.GET, RequestMethod.OPTIONS})
    public CompletableFuture<ResponseEntity<Map<String, Object>>> processAction(@RequestBody(required = false) Map<String, Object> payload) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (payload == null || payload.isEmpty()) return ResponseEntity.ok(Map.of("status", "online"));
                String action = (String) payload.get("action");
                if ("ping_test".equals(action)) return ResponseEntity.ok(Map.of("status", "online"));
                
                @SuppressWarnings("unchecked")
                Map<String, Object> company = (Map<String, Object>) payload.get("company");
                if (action == null || company == null) return ResponseEntity.badRequest().body(Map.of("error", "Atributos ausentes."));

                String certUri = (String) company.get("certificate_file_uri");
                String certPass = (String) company.get("certificate_password");
                String ufString = (String) company.get("uf");
                String ambienteStr = (String) company.getOrDefault("ambiente", "HOMOLOGACAO");

                byte[] pfxBytes = downloadCertificado(certUri);
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(new ByteArrayInputStream(pfxBytes), certPass.toCharArray());

                NFeConfig config = new NFeConfig() {
                    @Override public DFUnidadeFederativa getCUF() { return ufString != null ? DFUnidadeFederativa.valueOfCodigo(ufString) : DFUnidadeFederativa.SP; }
                    @Override public DFAmbiente getAmbiente() { return "PRODUCAO".equalsIgnoreCase(ambienteStr) ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO; }
                    @Override public KeyStore getCertificadoKeyStore() { return ks; }
                    @Override public String getCertificadoSenha() { return certPass; }
                    @Override public KeyStore getCadeiaCertificadosKeyStore() { return ks; }
                    @Override public String getCadeiaCertificadosSenha() { return certPass; }
                };

                Map<String, Object> result = new HashMap<>();
                WSFacade wsFacade = new WSFacade(config); 

                switch (action) {
                    case "emitir": result = handleEmitir(payload, wsFacade, config); break;
                    case "consultar_status": result = handleConsultarStatus(payload, wsFacade, config.getCUF()); break;
                    default: return ResponseEntity.badRequest().body(Map.of("error", "Ação não suportada"));
                }
                return ResponseEntity.ok(result);
            } catch (Exception e) {
                e.printStackTrace();
                return ResponseEntity.status(500).body(Map.of("status", "error", "message", e.getMessage()));
            }
        }, filaDeProcessamento);
    }

    private byte[] downloadCertificado(String uriString) throws Exception {
        URL url = new URL(uriString);
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try (InputStream in = connection.getInputStream()) { return in.readAllBytes(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleEmitir(Map<String, Object> payload, WSFacade wsFacade, NFeConfig config) throws Exception {
        NFNota nota = new NFNota();
        NFNotaInfo info = new NFNotaInfo();
        
        // 1. Bloco IDE (Identificação)
        NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
        ide.setUf(config.getCUF());
        ide.setAmbiente(config.getAmbiente());
        ide.setModelo(DFModelo.NFE);
        ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
        
        // >>> A ÚNICA LINHA QUE FALTAVA NA SUA PRIMEIRA VERSÃO PARA A CHAVE NÃO DAR NULL <<<
        ide.setTipoEmissao(com.fincatto.documentofiscal.nfe.NFTipoEmissao.EMISSAO_NORMAL);

        Map<String, Object> invoiceData = payload.containsKey("invoice") ? (Map<String, Object>) payload.get("invoice") : new HashMap<>();
        ide.setNaturezaOperacao((String) invoiceData.getOrDefault("natureza_operacao", "VENDA DE MERCADORIA"));
        ide.setSerie(invoiceData.containsKey("serie") ? invoiceData.get("serie").toString() : "1");
        ide.setNumeroNota(invoiceData.containsKey("numero") ? invoiceData.get("numero").toString() : "1");
        ide.setDataHoraEmissao(ZonedDateTime.now());
        info.setIdentificacao(ide);
        
        // 2. Bloco Emitente (Remetente)
        Map<String, Object> companyData = payload.containsKey("company") ? (Map<String, Object>) payload.get("company") : new HashMap<>();
        NFNotaInfoEmitente emitente = new NFNotaInfoEmitente();
        emitente.setCnpj((String) companyData.getOrDefault("cnpj", "00000000000000"));
        emitente.setRazaoSocial((String) companyData.getOrDefault("razao_social", "EMPRESA TESTE LTDA"));
        emitente.setNomeFantasia((String) companyData.getOrDefault("nome_fantasia", "EMPRESA TESTE"));
        emitente.setInscricaoEstadual((String) companyData.getOrDefault("inscricao_estadual", "ISENTO"));
        
        NFEndereco enderecoEmitente = new NFEndereco(); 
        enderecoEmitente.setLogradouro((String) companyData.getOrDefault("logradouro", "RUA DE TESTE"));
        enderecoEmitente.setNumero((String) companyData.getOrDefault("numero", "123"));
        enderecoEmitente.setBairro((String) companyData.getOrDefault("bairro", "CENTRO"));
        enderecoEmitente.setCodigoMunicipio((String) companyData.getOrDefault("codigo_municipio", "3516200")); // Mantive Franca
        enderecoEmitente.setDescricaoMunicipio((String) companyData.getOrDefault("municipio", "Franca"));
        enderecoEmitente.setUf(config.getCUF());
        enderecoEmitente.setCep((String) companyData.getOrDefault("cep", "14400000"));
        emitente.setEndereco(enderecoEmitente);
        info.setEmitente(emitente);
        
        // 3. Bloco Destinatário (Cliente)
        Map<String, Object> customerData = payload.containsKey("customer") ? (Map<String, Object>) payload.get("customer") : new HashMap<>();
        NFNotaInfoDestinatario destinatario = new NFNotaInfoDestinatario();
        String docCliente = (String) customerData.getOrDefault("documento", "00000000000");
        if (docCliente.length() > 11) { destinatario.setCnpj(docCliente); } else { destinatario.setCpf(docCliente); }
        destinatario.setRazaoSocial((String) customerData.getOrDefault("nome", "CLIENTE TESTE CONSUMIDOR"));
        destinatario.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);
        
        NFEndereco enderecoDestinatario = new NFEndereco(); 
        enderecoDestinatario.setLogradouro((String) customerData.getOrDefault("logradouro", "RUA DO CLIENTE"));
        enderecoDestinatario.setNumero((String) customerData.getOrDefault("numero", "S/N"));
        enderecoDestinatario.setBairro((String) customerData.getOrDefault("bairro", "CENTRO"));
        enderecoDestinatario.setCodigoMunicipio((String) customerData.getOrDefault("codigo_municipio", "3550308")); 
        enderecoDestinatario.setDescricaoMunicipio((String) customerData.getOrDefault("municipio", "SAO PAULO"));
        enderecoDestinatario.setUf(DFUnidadeFederativa.SP);
        enderecoDestinatario.setCep((String) customerData.getOrDefault("cep", "01001000"));
        destinatario.setEndereco(enderecoDestinatario);
        info.setDestinatario(destinatario);

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

    private Map<String, Object> handleConsultarStatus(Map<String, Object> payload, WSFacade wsFacade, DFUnidadeFederativa uf) throws Exception {
        NFStatusServicoConsultaRetorno retorno = wsFacade.consultaStatus(uf, DFModelo.NFE);
        return Map.of("status", retorno.getStatus() != null ? retorno.getStatus() : "erro", "motivo", retorno.getMotivo());
    }
}
// Sync: 2026-04-24T12:53:13.723Z