package com.sellerx.motorfiscal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.security.KeyStore;
import java.util.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;

import com.fincatto.documentofiscal.nfe400.webservices.WSFacade;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.DFAmbiente;
import com.fincatto.documentofiscal.DFUnidadeFederativa;
import com.fincatto.documentofiscal.DFModelo;
import com.fincatto.documentofiscal.nfe400.classes.nota.*;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.*;

@RestController
@RequestMapping("/api/nfe")
@CrossOrigin("*")
public class NfeController {

    @PostMapping("/process")
    public ResponseEntity<?> process(@RequestBody Map<String, Object> payload) {
        try {
            String action = (String) payload.get("action");
            if ("ping".equals(action)) return ResponseEntity.ok(Map.of("status", "online"));

            @SuppressWarnings("unchecked") Map<String, Object> company = (Map<String, Object>) payload.get("company");
            @SuppressWarnings("unchecked") Map<String, Object> invoice = (Map<String, Object>) payload.get("invoice");
            @SuppressWarnings("unchecked") Map<String, Object> customer = (Map<String, Object>) payload.get("customer");
            @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

            String certUri = (String) company.get("certificate_file_uri");
            String certPass = (String) company.get("certificate_password");
            
            URL url = new URL(certUri);
            byte[] pfx;
            try (InputStream in = url.openStream()) { pfx = in.readAllBytes(); }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pfx), certPass.toCharArray());

            NFeConfig config = new NFeConfig() {
                @Override public DFUnidadeFederativa getCUF() { return DFUnidadeFederativa.valueOfCodigo(String.valueOf(company.getOrDefault("uf_codigo", "35"))); }
                @Override public DFAmbiente getAmbiente() { return "PRODUCAO".equals(company.get("ambiente")) ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO; }
                @Override public KeyStore getCertificadoKeyStore() { return ks; }
                @Override public String getCertificadoSenha() { return certPass; }
                @Override public KeyStore getCadeiaCertificadosKeyStore() { return ks; }
                @Override public String getCadeiaCertificadosSenha() { return certPass; }
            };

            NFNota nota = new NFNota();
            NFNotaInfo info = new NFNotaInfo();
            
            // ---> PATCH: XML VALIDATION FIX <---
            info.setVersao("4.00");
            info.setIdentificador("00000000000000000000000000000000000000000000"); 

            // 1. IDENTIFICAÇÃO (BLINDADO)
            NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
            ide.setUf(config.getCUF());
            ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
            ide.setNaturezaOperacao(String.valueOf(invoice.getOrDefault("natureza_operacao", "VENDA")));
            ide.setModelo(DFModelo.NFE);
            ide.setSerie(String.valueOf(invoice.getOrDefault("serie", "1")));
            ide.setNumeroNota(String.valueOf(invoice.getOrDefault("numero", "1")));
            ide.setDataHoraEmissao(ZonedDateTime.now());
            
            ide.setTipoDocumento(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoTipoDocumento.valueOfCodigo("1")); // 1-Saída
            ide.setIdentificadorLocalDestinoOperacao(com.fincatto.documentofiscal.nfe400.classes.NFIdentificadorLocalDestinoOperacao.valueOfCodigo("1")); // 1-Interna
            ide.setCodigoMunicipio(String.valueOf(company.get("codigo_municipio")));
            ide.setTipoImpressao(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoTipoImpressao.valueOfCodigo("1")); // 1-Danfe Normal
            ide.setTipoEmissao(com.fincatto.documentofiscal.nfe.NFTipoEmissao.valueOfCodigo("1")); // 1-Normal
            ide.setAmbiente(config.getAmbiente());
            ide.setFinalidade(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoFinalidade.valueOfCodigo("1")); // 1-Normal
            ide.setOperacaoConsumidorFinal(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoOperacaoConsumidorFinal.valueOfCodigo(String.valueOf(invoice.getOrDefault("consumidor_final", "1"))));
            ide.setIndicadorPresencaComprador(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoIndPresencaComprador.valueOfCodigo(String.valueOf(invoice.getOrDefault("presenca", "1"))));
            info.setIdentificacao(ide);

            // 2. EMITENTE
            NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
            emit.setCnpj(String.valueOf(company.get("cnpj")).replaceAll("[^0-9]", ""));
            emit.setRazaoSocial(String.valueOf(company.get("razao_social")));
            emit.setInscricaoEstadual(String.valueOf(company.get("inscricao_estadual")));
            emit.setCrt(com.fincatto.documentofiscal.nfe400.classes.CRT.valueOfCodigo(String.valueOf(company.getOrDefault("crt", "1"))));
            
            NFEndereco endE = new NFEndereco();
            endE.setLogradouro(String.valueOf(company.get("logradouro")));
            endE.setNumero(String.valueOf(company.get("numero")));
            endE.setBairro(String.valueOf(company.get("bairro")));
            endE.setCodigoMunicipio(String.valueOf(company.get("codigo_municipio")));
            endE.setDescricaoMunicipio(String.valueOf(company.get("municipio")));
            endE.setUf(config.getCUF());
            endE.setCep(String.valueOf(company.get("cep")).replaceAll("[^0-9]", ""));
            emit.setEndereco(endE);
            info.setEmitente(emit);

            // 3. DESTINATÁRIO
            NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
            String docDest = String.valueOf(customer.get("documento")).replaceAll("[^0-9]", "");
            if(docDest.length() > 11) dest.setCnpj(docDest); else dest.setCpf(docDest);
            dest.setRazaoSocial(String.valueOf(customer.get("nome")));
            dest.setIndicadorIEDestinatario(com.fincatto.documentofiscal.nfe400.classes.NFIndicadorIEDestinatario.valueOfCodigo(String.valueOf(customer.getOrDefault("indicador_ie", "9"))));
            
            NFEndereco endD = new NFEndereco();
            endD.setLogradouro(String.valueOf(customer.get("logradouro")));
            endD.setNumero(String.valueOf(customer.get("numero")));
            endD.setBairro(String.valueOf(customer.get("bairro")));
            endD.setCodigoMunicipio(String.valueOf(customer.get("codigo_municipio")));
            endD.setDescricaoMunicipio(String.valueOf(customer.get("municipio")));
            endD.setUf(DFUnidadeFederativa.valueOfCodigo(String.valueOf(customer.getOrDefault("uf_codigo", "35"))));
            endD.setCep(String.valueOf(customer.get("cep")).replaceAll("[^0-9]", ""));
            dest.setEndereco(endD);
            info.setDestinatario(dest);

            // 4. ITENS E TOTAIS DINÂMICOS
            List<NFNotaInfoItem> listaItens = new ArrayList<>();
            BigDecimal totalProdutos = BigDecimal.ZERO;
            int ordem = 1;

            for (Map<String, Object> itemData : items) {
                NFNotaInfoItem item = new NFNotaInfoItem();
                item.setNumeroOrdem(ordem++);

                NFNotaInfoItemProduto prod = new NFNotaInfoItemProduto();
                prod.setCodigo(String.valueOf(itemData.get("codigo")));
                prod.setDescricao(String.valueOf(itemData.get("descricao")));
                prod.setNcm(String.valueOf(itemData.get("ncm")));
                prod.setCfop(String.valueOf(itemData.get("cfop")));
                prod.setUnidadeComercial(String.valueOf(itemData.getOrDefault("unidade", "UN")));
                prod.setUnidadeTributavel(String.valueOf(itemData.getOrDefault("unidade", "UN")));
                
                BigDecimal qtd = new BigDecimal(String.valueOf(itemData.get("quantidade")));
                BigDecimal valorUnit = new BigDecimal(String.valueOf(itemData.get("valor_unitario")));
                BigDecimal valorTotalItem = qtd.multiply(valorUnit).setScale(2, RoundingMode.HALF_UP);
                totalProdutos = totalProdutos.add(valorTotalItem);

                prod.setQuantidadeComercial(qtd);
                prod.setQuantidadeTributavel(qtd);
                prod.setValorUnitario(valorUnit);
                prod.setValorUnitarioTributavel(valorUnit);
                prod.setValorTotalBruto(valorTotalItem);
                prod.setIndicaTotal(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoItemIndicadorTotal.valueOfCodigo("1"));
                item.setProduto(prod);

                // Imposto (Simples Nacional CSOSN 102 Padrão ou Dinâmico)
                NFNotaInfoItemImposto imp = new NFNotaInfoItemImposto();
                NFNotaInfoItemImpostoICMS icms = new NFNotaInfoItemImpostoICMS();
                NFNotaInfoItemImpostoICMSSN102 sn = new NFNotaInfoItemImpostoICMSSN102();
                sn.setOrigem(com.fincatto.documentofiscal.nfe400.classes.NFOrigem.valueOfCodigo("0"));
                sn.setSituacaoOperacaoSN(com.fincatto.documentofiscal.nfe400.classes.NFNotaSituacaoOperacionalSimplesNacional.valueOfCodigo(String.valueOf(itemData.getOrDefault("csosn", "102"))));
                icms.setIcmsSn102(sn);
                imp.setIcms(icms);
                
                // Pis e Cofins zerados obrigatorios
                NFNotaInfoItemImpostoPIS pis = new NFNotaInfoItemImpostoPIS();
                NFNotaInfoItemImpostoPISOutras pisOutras = new NFNotaInfoItemImpostoPISOutras();
                pisOutras.setSituacaoTributaria(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoSituacaoTributariaPIS.valueOfCodigo("99"));
                pisOutras.setValorBaseCalculo(BigDecimal.ZERO); pisOutras.setPercentualAliquota(BigDecimal.ZERO); pisOutras.setValorTributo(BigDecimal.ZERO);
                pis.setOutras(pisOutras); imp.setPis(pis);

                NFNotaInfoItemImpostoCOFINS cofins = new NFNotaInfoItemImpostoCOFINS();
                NFNotaInfoItemImpostoCOFINSOutras cofOutras = new NFNotaInfoItemImpostoCOFINSOutras();
                cofOutras.setSituacaoTributaria(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoSituacaoTributariaCOFINS.valueOfCodigo("99"));
                cofOutras.setValorBaseCalculo(BigDecimal.ZERO); cofOutras.setPercentualAliquota(BigDecimal.ZERO); cofOutras.setValorTributo(BigDecimal.ZERO);
                cofins.setOutras(cofOutras); imp.setCofins(cofins);

                item.setImposto(imp);
                listaItens.add(item);
            }
            info.setItens(listaItens);

            // 5. TOTAIS
            NFNotaInfoTotal total = new NFNotaInfoTotal();
            NFNotaInfoICMSTotal icmsT = new NFNotaInfoICMSTotal();
            icmsT.setBaseCalculoICMS(BigDecimal.ZERO);
            icmsT.setValorTotalICMS(BigDecimal.ZERO);
            icmsT.setValorICMSDesonerado(BigDecimal.ZERO);
            icmsT.setBaseCalculoICMSST(BigDecimal.ZERO);
            icmsT.setValorTotalICMSST(BigDecimal.ZERO);
            icmsT.setValorProdutoServico(totalProdutos);
            icmsT.setValorFrete(BigDecimal.ZERO);
            icmsT.setValorSeguro(BigDecimal.ZERO);
            icmsT.setValorDesconto(BigDecimal.ZERO);
            icmsT.setValorOutrasDespesas(BigDecimal.ZERO);
            icmsT.setValorTotalIPI(BigDecimal.ZERO);
            icmsT.setValorPIS(BigDecimal.ZERO);
            icmsT.setValorCOFINS(BigDecimal.ZERO);
            icmsT.setValorTotalNFe(totalProdutos);
            total.setIcmsTotal(icmsT);
            info.setTotal(total);

            // 6. TRANSPORTE
            NFNotaInfoTransporte trans = new NFNotaInfoTransporte();
            trans.setModalidadeFrete(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoModalidadeFrete.valueOfCodigo(String.valueOf(invoice.getOrDefault("modalidade_frete", "9"))));
            info.setTransporte(trans);

            // 7. PAGAMENTO
            NFNotaInfoPagamento pag = new NFNotaInfoPagamento();
            com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoPagamentoDetalhe det = new com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoPagamentoDetalhe();
            det.setFormaPagamento(com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoFormaPagamento.valueOfCodigo(String.valueOf(invoice.getOrDefault("forma_pagamento", "01"))));
            det.setValorPagamento(totalProdutos);
            pag.setDetalhamentoPagamentos(Collections.singletonList(det));
            info.setPagamento(pag);

            nota.setInfo(info);
            
            // 8. ENVIO PARA SEFAZ
            NFLoteEnvio lote = new NFLoteEnvio();
            lote.setNotas(Collections.singletonList(nota));
            lote.setIdLote("1");
            lote.setVersao("4.00");
            lote.setIndicadorProcessamento(com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteIndicadorProcessamento.valueOfCodigo("1"));

            WSFacade ws = new WSFacade(config);
            NFLoteEnvioRetornoDados res = ws.enviaLote(lote);
            
            return ResponseEntity.ok(Map.of(
                "status", res.getRetorno().getStatus(),
                "motivo", res.getRetorno().getMotivo(),
                "recibo", res.getRetorno().getInfoRecebimento() != null ? res.getRetorno().getInfoRecebimento().getRecibo() : ""
            ));

        } catch (Exception e) { 
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("erro", e.getMessage())); 
        }
    }
    @GetMapping("/process") public ResponseEntity<?> ping() { return ResponseEntity.ok(Map.of("status", "online")); }
}
// Sync: 2026-04-24T13:21:02.714Z