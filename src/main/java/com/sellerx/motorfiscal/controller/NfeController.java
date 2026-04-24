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
            
            // PATCH: FIX DO XML SIMPLES
            info.setVersao("4.00");

            // 1. IDENTIFICAÇÃO (COMPILAÇÃO SEGURA)
            NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
            ide.setUf(config.getCUF());
            ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
            ide.setNaturezaOperacao(String.valueOf(invoice.getOrDefault("natureza_operacao", "VENDA")));
            ide.setModelo(DFModelo.NFE);
            ide.setSerie(String.valueOf(invoice.getOrDefault("serie", "1")));
            ide.setNumeroNota(String.valueOf(invoice.getOrDefault("numero", "1")));
            ide.setDataHoraEmissao(ZonedDateTime.now());
            ide.setTipoEmissao(NFTipoEmissao.EMISSAO_NORMAL);
            info.setIdentificacao(ide);

            // 2. EMITENTE
            NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
            emit.setCnpj(String.valueOf(company.get("cnpj")).replaceAll("[^0-9]", ""));
            emit.setRazaoSocial(String.valueOf(company.get("razao_social")));
            emit.setInscricaoEstadual(String.valueOf(company.get("inscricao_estadual")));
            
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

            // 4. ITENS DINÂMICOS
            List<NFNotaInfoItem> listaItens = new ArrayList<>();
            int ordem = 1;

            if (items != null) {
                for (Map<String, Object> itemData : items) {
                    NFNotaInfoItem item = new NFNotaInfoItem();
                    item.setNumeroOrdem(Integer.valueOf(ordem++)); // Evitando conflito int/Integer

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

                    prod.setQuantidadeComercial(qtd);
                    prod.setQuantidadeTributavel(qtd);
                    prod.setValorUnitario(valorUnit);
                    prod.setValorUnitarioTributavel(valorUnit);
                    prod.setValorTotalBruto(valorTotalItem);
                    item.setProduto(prod);
                    
                    listaItens.add(item);
                }
            }
            info.setItens(listaItens);

            nota.setInfo(info);
            
            // 8. ENVIO PARA SEFAZ
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
            return ResponseEntity.status(500).body(Map.of("erro", e.getMessage())); 
        }
    }
    @GetMapping("/process") public ResponseEntity<?> ping() { return ResponseEntity.ok(Map.of("status", "online")); }
}
// Prod Sync: 2026-04-24T14:21:38.420Z