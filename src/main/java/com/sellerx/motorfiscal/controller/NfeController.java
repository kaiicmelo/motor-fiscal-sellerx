package com.sellerx.motorfiscal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.*;
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
        String numNotaLog = "0";
        try {
            if (payload == null || payload.get("company") == null) return ResponseEntity.badRequest().body(Map.of("erro", "JSON incompleto"));

            Map<String, Object> company = (Map<String, Object>) payload.get("company");
            Map<String, Object> invoice = (Map<String, Object>) payload.get("invoice");
            Map<String, Object> customer = (Map<String, Object>) payload.get("customer");
            List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

            numNotaLog = String.valueOf(invoice.getOrDefault("numero", invoice.getOrDefault("nextNum", "1")));

            // CERTIFICADO
            URL url = new URL((String) company.get("certificate_file_uri"));
            String certPass = (String) company.get("certificate_password");
            byte[] pfx;
            try (InputStream in = url.openStream()) { pfx = in.readAllBytes(); }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pfx), certPass != null ? certPass.toCharArray() : new char[0]);

            NFeConfig config = new NFeConfig() {
                @Override public DFUnidadeFederativa getCUF() { return DFUnidadeFederativa.valueOfCodigo(String.valueOf(company.getOrDefault("uf_codigo", "35"))); }
                @Override public DFAmbiente getAmbiente() { return "PRODUCAO".equals(company.get("ambiente")) ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO; }
                @Override public KeyStore getCertificadoKeyStore() { return ks; }
                @Override public String getCertificadoSenha() { return certPass; }
                @Override public KeyStore getCadeiaCertificadosKeyStore() { return ks; }
                @Override public String getCadeiaCertificadosSenha() { return certPass; }
            };

            Persister xmlParser = new Persister();
            NFNota nota = new NFNota();
            NFNotaInfo info = new NFNotaInfo();
            info.setVersao(new BigDecimal("4.00"));

            // IDENTIFICAÇÃO (IDE)
            NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
            ide.setUf(config.getCUF());
            ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
            ide.setNaturezaOperacao(String.valueOf(invoice.getOrDefault("natureza_operacao", "VENDA")));
            ide.setModelo(DFModelo.NFE);
            ide.setSerie(String.valueOf(invoice.getOrDefault("serie", "1")));
            ide.setNumeroNota(numNotaLog);
            ide.setDataHoraEmissao(ZonedDateTime.now());
            ide.setTipoEmissao(NFTipoEmissao.EMISSAO_NORMAL);
            ide.setTipo(NFTipo.SAIDA);
            ide.setIdentificadorLocalDestinoOperacao(NFIdentificadorLocalDestinoOperacao.OPERACAO_INTERNA);
            ide.setCodigoMunicipio(String.valueOf(company.getOrDefault("codigo_municipio", "3516200")));
            ide.setTipoImpressao(NFTipoImpressao.DANFE_NORMAL_RETRATO);
            ide.setFinalidade(NFFinalidade.NORMAL);
            ide.setOperacaoConsumidorFinal(NFOperacaoConsumidorFinal.SIM);
            ide.setIndicadorPresencaComprador(NFIndicadorPresencaComprador.valueOfCodigo("2"));
            ide.setProgramaEmissor(NFProcessoEmissor.CONTRIBUINTE);
            ide.setVersaoEmissor("1.0.5");
            info.setIdentificacao(ide);

            // EMITENTE
            NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
            emit.setCnpj(String.valueOf(company.getOrDefault("cnpj", "")).replaceAll("[^0-9]", ""));
            emit.setRazaoSocial(String.valueOf(company.getOrDefault("razao_social", "EMPRESA")));
            emit.setInscricaoEstadual(String.valueOf(company.getOrDefault("inscricao_estadual", "")));
            emit.setRegimeTributario(NFRegimeTributario.SIMPLES_NACIONAL);
            info.setEmitente(emit);

            // DESTINATÁRIO
            NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
            String docDest = String.valueOf(customer.getOrDefault("documento", "")).replaceAll("[^0-9]", "");
            if(docDest.length() > 11) dest.setCnpj(docDest); else if(!docDest.isEmpty()) dest.setCpf(docDest);
            dest.setRazaoSocial(String.valueOf(customer.getOrDefault("nome", "CONSUMIDOR")));
            dest.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);
            info.setDestinatario(dest);

            // ITENS (TRATAMENTO DE NULOS E VÍRGULAS)
            List<NFNotaInfoItem> listaItens = new ArrayList<>();
            BigDecimal totalProdutos = BigDecimal.ZERO;
            int ordem = 1;

            for (Map<String, Object> itemData : items) {
                NFNotaInfoItem item = new NFNotaInfoItem();
                item.setNumeroItem(ordem++);
                NFNotaInfoItemProduto prod = new NFNotaInfoItemProduto();
                prod.setCodigo(String.valueOf(itemData.getOrDefault("codigo", "1")));
                prod.setDescricao(String.valueOf(itemData.getOrDefault("descricao", "PRODUTO")));
                prod.setNcm(String.valueOf(itemData.getOrDefault("ncm", "00000000")));
                prod.setCfop(String.valueOf(itemData.getOrDefault("cfop", "5102")));
                prod.setUnidadeComercial("UN");
                prod.setUnidadeTributavel("UN");
                
                // Conversão Segura de Números
                Object qVal = itemData.get("quantidade");
                BigDecimal qtd = (qVal != null && !String.valueOf(qVal).isEmpty()) ? 
                    new BigDecimal(String.valueOf(qVal).replace(",", ".")) : BigDecimal.ONE;
                
                Object vVal = itemData.get("valor_unitario");
                BigDecimal vUnit = (vVal != null && !String.valueOf(vVal).isEmpty()) ? 
                    new BigDecimal(String.valueOf(vVal).replace(",", ".")) : BigDecimal.ZERO;
                
                BigDecimal vTotal = qtd.multiply(vUnit).setScale(2, RoundingMode.HALF_UP);
                totalProdutos = totalProdutos.add(vTotal);
                
                prod.setQuantidadeComercial(qtd);
                prod.setQuantidadeTributavel(qtd);
                prod.setValorUnitario(vUnit); // vUnCom resolvido!
                prod.setValorUnitarioTributavel(vUnit);
                prod.setValorTotalBruto(vTotal);
                item.setProduto(prod);

                // IMPOSTO VIA OBJETO (SEGURO)
                NFNotaInfoItemImposto imposto = new NFNotaInfoItemImposto();
                NFNotaInfoItemImpostoICMS icms = new NFNotaInfoItemImpostoICMS();
                NFNotaInfoItemImpostoICMSSN102 icms102 = new NFNotaInfoItemImpostoICMSSN102();
                icms102.setOrigem(NFOrigem.NACIONAL);
                icms102.setSituacaoOperacaoSN(NFSituacaoOperacaoSimplesNacional.valueOfCodigo("102"));
                icms.setIcmssn102(icms102);
                imposto.setIcms(icms);
                item.setImposto(imposto);
                
                listaItens.add(item);
            }
            info.setItens(listaItens);

            // TOTAIS E PAGAMENTO
            String vTot = totalProdutos.setScale(2, RoundingMode.HALF_UP).toPlainString();
            String xmlTot = "<total><ICMSTot><vBC>0.00</vBC><vICMS>0.00</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP><vBCST>0.00</vBCST><vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet><vProd>"+vTot+"</vProd><vFrete>0.00</vFrete><vSeg>0.00</vSeg><vDesc>0.00</vDesc><vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>0.00</vPIS><vCOFINS>0.00</vCOFINS><vOutro>0.00</vOutro><vNF>"+vTot+"</vNF></ICMSTot></total>";
            info.setTotal(xmlParser.read(NFNotaInfoTotal.class, xmlTot));

            String xmlPag = "<pag><detPag><tPag>01</tPag><vPag>"+vTot+"</vPag></detPag></pag>";
            info.setPagamento(xmlParser.read(NFNotaInfoPagamento.class, xmlPag));

            NFNotaInfoTransporte transp = new NFNotaInfoTransporte();
            transp.setModalidadeFrete(NFModalidadeFrete.valueOfCodigo("9"));
            info.setTransporte(transp);

            nota.setInfo(info);
            NFLoteEnvio lote = new NFLoteEnvio();
            lote.setNotas(Collections.singletonList(nota));
            lote.setIdLote("1");
            lote.setVersao("4.00");

            WSFacade ws = new WSFacade(config);
            NFLoteEnvioRetornoDados res = ws.enviaLote(lote);
            
            return ResponseEntity.ok(Map.of("status", res.getRetorno().getStatus(), "motivo", res.getRetorno().getMotivo() != null ? res.getRetorno().getMotivo() : "OK", "nota", numNotaLog));

        } catch (Exception e) {
            StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw));
            return ResponseEntity.status(500).body(Map.of("erro", e.getMessage(), "detalhes", sw.toString().substring(0, Math.min(sw.toString().length(), 800))));
        }
    }
    @GetMapping("/process") public ResponseEntity<?> ping() { return ResponseEntity.ok(Map.of("status", "online")); }
}
// Cache: 1777063575255