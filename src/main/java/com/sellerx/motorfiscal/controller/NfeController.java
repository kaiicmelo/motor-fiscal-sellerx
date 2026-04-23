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
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.ZonedDateTime;
import java.util.Random;

// Imports Essenciais do Fincatto
import com.fincatto.documentofiscal.nfe400.classes.nota.*;
import com.fincatto.documentofiscal.nfe400.classes.nota.imposto.*;
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
                if (payload == null || payload.isEmpty()) {
                    return ResponseEntity.ok(Map.of("status", "online", "message", "Motor Fiscal rodando e mapeado perfeitamente."));
                }

                String action = (String) payload.get("action");
                if ("ping_test".equals(action)) {
                    return ResponseEntity.ok(Map.of("status", "online", "message", "Ping test concluído. A rota responde perfeitamente."));
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> company = (Map<String, Object>) payload.get("company");
                if (action == null || company == null || company.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Atributos 'action' ou 'company' ausentes."));
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
                    case "emitir":
                        result = handleEmitir(payload, wsFacade, config);
                        break;
                    case "consultar_status":
                        result = handleConsultarStatus(payload, wsFacade, config.getCUF());
                        break;
                    default:
                        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Ação não suportada: " + action));
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
        Map<String, Object> invoiceData = payload.containsKey("invoice") ? (Map<String, Object>) payload.get("invoice") : new HashMap<>();
        ide.setNaturezaOperacao((String) invoiceData.getOrDefault("natureza_operacao", "VENDA DE MERCADORIA"));
        ide.setSerie(invoiceData.containsKey("serie") ? invoiceData.get("serie").toString() : "1");
        ide.setNumeroNota(invoiceData.containsKey("numero") ? invoiceData.get("numero").toString() : "1");
        ide.setDataHoraEmissao(ZonedDateTime.now());
        ide.setTipoImpressao(NFNotaInfoTipoImpressao.DANFE_NORMAL_RETRATO);
        ide.setTipoEmissao(NFNotaInfoTipoEmissao.EMISSAO_NORMAL);
        ide.setFinalidade(NFNotaInfoFinalidade.NORMAL);
        info.setIdentificacao(ide);
        
        // 2. Bloco Emitente (Remetente)
        Map<String, Object> companyData = payload.containsKey("company") ? (Map<String, Object>) payload.get("company") : new HashMap<>();
        NFNotaInfoEmitente emitente = new NFNotaInfoEmitente();
        emitente.setCnpj((String) companyData.getOrDefault("cnpj", "00000000000000"));
        emitente.setRazaoSocial((String) companyData.getOrDefault("razao_social", "EMPRESA TESTE LTDA"));
        emitente.setNomeFantasia((String) companyData.getOrDefault("nome_fantasia", "EMPRESA TESTE"));
        emitente.setInscricaoEstadual((String) companyData.getOrDefault("inscricao_estadual", "ISENTO"));
        emitente.setCrt(NFNotaInfoRegimeTributario.SIMPLES_NACIONAL); // CORREÇÃO AQUI
        
        NFEndereco enderecoEmitente = new NFEndereco(); // CORREÇÃO AQUI
        enderecoEmitente.setLogradouro((String) companyData.getOrDefault("logradouro", "RUA DE TESTE"));
        enderecoEmitente.setNumero((String) companyData.getOrDefault("numero", "123"));
        enderecoEmitente.setBairro((String) companyData.getOrDefault("bairro", "CENTRO"));
        enderecoEmitente.setCodigoMunicipio((String) companyData.getOrDefault("codigo_municipio", "3516200")); // Franca/SP
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
        
        NFEndereco enderecoDestinatario = new NFEndereco(); // CORREÇÃO AQUI
        enderecoDestinatario.setLogradouro((String) customerData.getOrDefault("logradouro", "RUA DO CLIENTE"));
        enderecoDestinatario.setNumero((String) customerData.getOrDefault("numero", "S/N"));
        enderecoDestinatario.setBairro((String) customerData.getOrDefault("bairro", "CENTRO"));
        enderecoDestinatario.setCodigoMunicipio((String) customerData.getOrDefault("codigo_municipio", "3550308")); // Default São Paulo
        enderecoDestinatario.setDescricaoMunicipio((String) customerData.getOrDefault("municipio", "SAO PAULO"));
        enderecoDestinatario.setUf(DFUnidadeFederativa.SP); // Fallback para SP
        enderecoDestinatario.setCep((String) customerData.getOrDefault("cep", "01001000"));
        destinatario.setEndereco(enderecoDestinatario);
        info.setDestinatario(destinatario);

        // 4. Bloco Produtos e Impostos
        List<Map<String, Object>> itemsData = payload.containsKey("items") ? (List<Map<String, Object>>) payload.get("items") : new ArrayList<>();
        List<NFNotaInfoItem> itensDaNota = new ArrayList<>();
        BigDecimal somaTotalProdutos = BigDecimal.ZERO;
        
        if (itemsData.isEmpty()) {
            itemsData.add(Map.of("descricao", "PRODUTO TESTE VIBE CODE", "valor", "100.00")); // Fallback anti-crash
        }

        int contador = 1;
        for (Map<String, Object> itemPayload : itemsData) {
            NFNotaInfoItem item = new NFNotaInfoItem();
            item.setNumeroOrdemItem(contador++);

            NFNotaInfoItemProduto produto = new NFNotaInfoItemProduto();
            produto.setCodigo((String) itemPayload.getOrDefault("codigo", "123"));
            produto.setCodigoDeBarras("SEM GTIN");
            produto.setDescricao((String) itemPayload.getOrDefault("descricao", "PRODUTO TESTE"));
            produto.setNcm((String) itemPayload.getOrDefault("ncm", "94039000"));
            produto.setCfop((String) itemPayload.getOrDefault("cfop", "5102"));
            produto.setUnidadeComercial("UN");
            
            BigDecimal qtde = new BigDecimal(itemPayload.getOrDefault("quantidade", "1").toString());
            BigDecimal valorUnit = new BigDecimal(itemPayload.getOrDefault("valor", "100.00").toString());
            BigDecimal valorTotal = qtde.multiply(valorUnit);
            somaTotalProdutos = somaTotalProdutos.add(valorTotal);

            produto.setQuantidadeComercial(qtde);
            produto.setValorUnitario(valorUnit);
            produto.setValorTotalBruto(valorTotal);
            produto.setCodigoDeBarrasTributavel("SEM GTIN");
            produto.setUnidadeTributavel("UN");
            produto.setQuantidadeTributavel(qtde);
            produto.setValorUnitarioTributavel(valorUnit);
            produto.setIndicaTotal(NFNotaInfoItemIndicadorTotal.VALOR_ITEM_COMPOE_TOTAL_NOTA);
            item.setProduto(produto);

            // Tributação (Simples Nacional - Sem crédito de ICMS e isento de PIS/COFINS)
            NFNotaInfoItemImposto imposto = new NFNotaInfoItemImposto();
            
            NFNotaInfoItemImpostoICMS icms = new NFNotaInfoItemImpostoICMS();
            NFNotaInfoItemImpostoICMSSN102 icmsSn102 = new NFNotaInfoItemImpostoICMSSN102();
            icmsSn102.setOrigem(NFOrigem.NACIONAL);
            icmsSn102.setSituacaoOperacaoSN(NFNotaSituacaoOperacionalSimplesNacional.TRIBUTADA_PELO_SIMPLES_SEM_PERMISSAO_DE_CREDITO);
            icms.setIcmsSn102(icmsSn102);
            imposto.setIcms(icms);

            NFNotaInfoItemImpostoPIS pis = new NFNotaInfoItemImpostoPIS();
            NFNotaInfoItemImpostoPISNT pisNt = new NFNotaInfoItemImpostoPISNT();
            pisNt.setSituacaoTributaria(NFNotaInfoSituacaoTributariaPIS.OPERACAO_SEM_INCIDENCIA_DA_CONTRIBUICAO);
            pis.setPisNaoTributado(pisNt);
            imposto.setPis(pis);

            NFNotaInfoItemImpostoCOFINS cofins = new NFNotaInfoItemImpostoCOFINS();
            NFNotaInfoItemImpostoCOFINSNT cofinsNt = new NFNotaInfoItemImpostoCOFINSNT();
            cofinsNt.setSituacaoTributaria(NFNotaInfoSituacaoTributariaCOFINS.OPERACAO_SEM_INCIDENCIA_DA_CONTRIBUICAO);
            cofins.setCofinsNaoTributado(cofinsNt);
            imposto.setCofins(cofins);

            item.setImposto(imposto);
            itensDaNota.add(item);
        }
        info.setItens(itensDaNota);

        // 5. Totais da Nota
        NFNotaInfoTotal total = new NFNotaInfoTotal();
        NFNotaInfoICMSTotal icmsTotal = new NFNotaInfoICMSTotal();
        icmsTotal.setBaseCalculoICMS(BigDecimal.ZERO);
        icmsTotal.setValorTotalICMS(BigDecimal.ZERO);
        icmsTotal.setValorICMSDesonerado(BigDecimal.ZERO);
        icmsTotal.setBaseCalculoICMSST(BigDecimal.ZERO);
        icmsTotal.setValorTotalICMSST(BigDecimal.ZERO);
        icmsTotal.setValorTotalProdutosServicos(somaTotalProdutos);
        icmsTotal.setValorTotalFrete(BigDecimal.ZERO);
        icmsTotal.setValorTotalSeguro(BigDecimal.ZERO);
        icmsTotal.setValorTotalDesconto(BigDecimal.ZERO);
        icmsTotal.setValorTotalII(BigDecimal.ZERO);
        icmsTotal.setValorTotalIPI(BigDecimal.ZERO);
        icmsTotal.setValorTotalIPIPreenchido(BigDecimal.ZERO);
        icmsTotal.setValorPIS(BigDecimal.ZERO);
        icmsTotal.setValorCOFINS(BigDecimal.ZERO);
        icmsTotal.setOutrasDespesasAcessorias(BigDecimal.ZERO);
        icmsTotal.setValorTotalNota(somaTotalProdutos);
        total.setIcmsTotal(icmsTotal);
        info.setTotal(total);

        // 6. Transporte e Frete
        NFNotaInfoTransporte transporte = new NFNotaInfoTransporte();
        transporte.setModalidadeFrete(NFNotaInfoModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE);
        info.setTransporte(transporte);

        // 7. Pagamento
        NFNotaInfoPagamento pagamento = new NFNotaInfoPagamento();
        List<NFNotaInfoPagamentoDetalhe> pagamentos = new ArrayList<>();
        NFNotaInfoPagamentoDetalhe pagDet = new NFNotaInfoPagamentoDetalhe();
        pagDet.setFormaPagamento(NFNotaInfoFormaPagamento.OUTROS);
        pagDet.setValorPagamento(somaTotalProdutos);
        pagamentos.add(pagDet);
        pagamento.setDetalhamentoPagamentos(pagamentos);
        info.setPagamento(pagamento);

        // FINALIZANDO A MONTAGEM
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

// Sync: 2026-04-23T19:42:16.743Z