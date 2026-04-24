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
import com.fincatto.documentofiscal.nfe.NFTipoEmissao;
import com.fincatto.documentofiscal.nfe400.classes.nota.*;
import com.fincatto.documentofiscal.nfe400.classes.*;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.*;
import org.simpleframework.xml.core.Persister;

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
            info.setVersao(new BigDecimal("4.00"));

            NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
            ide.setUf(config.getCUF());
            ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
            ide.setNaturezaOperacao(String.valueOf(invoice.getOrDefault("natureza_operacao", "VENDA")));
            ide.setModelo(DFModelo.NFE);
            ide.setSerie(String.valueOf(invoice.getOrDefault("serie", "1")));
            ide.setNumeroNota(String.valueOf(invoice.getOrDefault("numero", "1")));
            ide.setDataHoraEmissao(ZonedDateTime.now());
            ide.setTipoEmissao(NFTipoEmissao.EMISSAO_NORMAL);
            
            ide.setTipo(NFTipo.SAIDA);
            ide.setIdentificadorLocalDestinoOperacao(NFIdentificadorLocalDestinoOperacao.OPERACAO_INTERNA);
            ide.setCodigoMunicipio(String.valueOf(company.get("codigo_municipio")));
            ide.setTipoImpressao(NFTipoImpressao.DANFE_NORMAL_RETRATO);
            ide.setFinalidade(NFFinalidade.NORMAL);
            ide.setOperacaoConsumidorFinal(NFOperacaoConsumidorFinal.SIM);
            ide.setIndicadorPresencaComprador(NFIndicadorPresencaComprador.OPERACAO_INTERNET);
            ide.setProgramaEmissor(NFProcessoEmissor.CONTRIBUINTE);
            ide.setVersaoEmissor("1.0.0");
            
            info.setIdentificacao(ide);

            NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
            emit.setCnpj(String.valueOf(company.get("cnpj")).replaceAll("[^0-9]", ""));
            emit.setRazaoSocial(String.valueOf(company.get("razao_social")));
            emit.setInscricaoEstadual(String.valueOf(company.get("inscricao_estadual")));
            emit.setRegimeTributario(NFRegimeTributario.SIMPLES_NACIONAL);
            
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

            NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
            String docDest = String.valueOf(customer.get("documento")).replaceAll("[^0-9]", "");
            if(docDest.length() > 11) dest.setCnpj(docDest); else dest.setCpf(docDest);
            dest.setRazaoSocial(String.valueOf(customer.get("nome")));
            dest.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);
            
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

            Persister xmlParser = new Persister();

            List<NFNotaInfoItem> listaItens = new ArrayList<>();
            BigDecimal totalProdutos = BigDecimal.ZERO;
            int ordem = 1;

            if (items != null) {
                for (Map<String, Object> itemData : items) {
                    NFNotaInfoItem item = new NFNotaInfoItem();
                    item.setNumeroItem(Integer.valueOf(ordem++));

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
                    prod.setIndicadorTotal(NFNotaInfoItemIndicadorTotal.VALOR_ITEM_COMPOE_TOTAL);
                    item.setProduto(prod);
                    
                    String xmlImposto = "<imposto><ICMS><ICMSSN102><orig>0</orig><CSOSN>102</CSOSN></ICMSSN102></ICMS><PIS><PISOutr><CST>99</CST><vBC>0.00</vBC><pPIS>0.00</pPIS><vPIS>0.00</vPIS></PISOutr></PIS><COFINS><COFINSOutr><CST>99</CST><vBC>0.00</vBC><pCOFINS>0.00</pCOFINS><vCOFINS>0.00</vCOFINS></COFINSOutr></COFINS></imposto>";
                    NFNotaInfoItemImposto imposto = xmlParser.read(NFNotaInfoItemImposto.class, xmlImposto, false);
                    item.setImposto(imposto);
                    
                    listaItens.add(item);
                }
            }
            info.setItens(listaItens);

            String xmlTotal = "<total><ICMSTot><vBC>0.00</vBC><vICMS>0.00</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP><vBCST>0.00</vBCST><vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet><vProd>" + totalProdutos.toString() + "</vProd><vFrete>0.00</vFrete><vSeg>0.00</vSeg><vDesc>0.00</vDesc><vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>0.00</vPIS><vCOFINS>0.00</vCOFINS><vOutro>0.00</vOutro><vNF>" + totalProdutos.toString() + "</vNF></ICMSTot></total>";
            NFNotaInfoTotal total = xmlParser.read(NFNotaInfoTotal.class, xmlTotal, false);
            info.setTotal(total);

            String modalidade = String.valueOf(invoice.getOrDefault("modalidade_frete", "9"));
            String xmlTransp = "<transp><modFrete>" + modalidade + "</modFrete></transp>";
            NFNotaInfoTransporte trans = xmlParser.read(NFNotaInfoTransporte.class, xmlTransp, false);
            info.setTransporte(trans);

            String formaPag = String.valueOf(invoice.getOrDefault("forma_pagamento", "01"));
            String xmlPag = "<pag><detPag><tPag>" + formaPag + "</tPag><vPag>" + totalProdutos.toString() + "</vPag></detPag></pag>";
            NFNotaInfoPagamento pag = xmlParser.read(NFNotaInfoPagamento.class, xmlPag, false);
            info.setPagamento(pag);

            nota.setInfo(info);
            
            NFLoteEnvio lote = new NFLoteEnvio();
            lote.setNotas(Collections.singletonList(nota));
            lote.setIdLote("1");
            lote.setVersao("4.00");

            WSFacade ws = new WSFacade(config);
            NFLoteEnvioRetornoDados res = ws.enviaLote(lote);
            
            return ResponseEntity.ok(Map.of(
                "status", res.getRetorno().getStatus(),
                "motivo", res.getRetorno().getMotivo(),
                "recibo", res.getRetorno().getInfoRecebimento() != null ? res.getRetorno().getInfoRecebimento().getRecibo() : ""
            ));

        } catch (Exception e) { 
            e.printStackTrace();
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Throwable cause = e.getCause();
            while (cause != null) {
                errorMsg += " | Detalhe: " + (cause.getMessage() != null ? cause.getMessage() : cause.toString());
                cause = cause.getCause();
            }
            return ResponseEntity.status(500).body(Map.of("erro", errorMsg, "status", "Motor Error")); 
        }
    }
    @GetMapping("/process") public ResponseEntity<?> ping() { return ResponseEntity.ok(Map.of("status", "online")); }
}
// Quebra Cache: 2026-04-24T18:03:32.373Z-xa4bd