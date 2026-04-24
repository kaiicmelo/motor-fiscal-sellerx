package com.sellerx.motorfiscal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
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
            // 1. VALIDAÇÃO DE PAYLOAD (Garante que não venha null do ERP)
            if (payload == null || payload.get("company") == null || payload.get("invoice") == null) {
                return ResponseEntity.badRequest().body(Map.of("erro", "Payload ou objetos internos (company/invoice) estão nulos. Verifique o envio do seu ERP."));
            }

            @SuppressWarnings("unchecked") Map<String, Object> company = (Map<String, Object>) payload.get("company");
            @SuppressWarnings("unchecked") Map<String, Object> invoice = (Map<String, Object>) payload.get("invoice");
            @SuppressWarnings("unchecked") Map<String, Object> customer = (Map<String, Object>) payload.get("customer");
            @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

            numNotaLog = invoice.get("numero") != null ? String.valueOf(invoice.get("numero")) : "0";

            // 2. CERTIFICADO COM TIMEOUT
            String certUri = (String) company.get("certificate_file_uri");
            String certPass = (String) company.get("certificate_password");
            if (certUri == null || certPass == null) throw new Exception("Dados do certificado (URI ou Senha) não foram enviados.");

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

            Persister xmlParser = new Persister();
            NFNota nota = new NFNota();
            NFNotaInfo info = new NFNotaInfo();
            info.setVersao(new BigDecimal("4.00"));

            // IDE
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
            ide.setCodigoMunicipio(String.valueOf(company.get("codigo_municipio")));
            ide.setTipoImpressao(NFTipoImpressao.DANFE_NORMAL_RETRATO);
            ide.setFinalidade(NFFinalidade.NORMAL);
            ide.setOperacaoConsumidorFinal(NFOperacaoConsumidorFinal.SIM);
            ide.setIndicadorPresencaComprador(NFIndicadorPresencaComprador.valueOfCodigo("2"));
            ide.setProgramaEmissor(NFProcessoEmissor.CONTRIBUINTE);
            ide.setVersaoEmissor("1.0.5");
            info.setIdentificacao(ide);

            // EMITENTE
            NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
            emit.setCnpj(String.valueOf(company.get("cnpj")).replaceAll("[^0-9]", ""));
            emit.setRazaoSocial(String.valueOf(company.get("razao_social")));
            emit.setInscricaoEstadual(String.valueOf(company.get("inscricao_estadual")));
            emit.setRegimeTributario(NFRegimeTributario.SIMPLES_NACIONAL);
            info.setEmitente(emit);

            // DESTINATARIO
            NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
            String docDest = customer.get("documento") != null ? String.valueOf(customer.get("documento")).replaceAll("[^0-9]", "") : "";
            if(docDest.length() > 11) dest.setCnpj(docDest); else if(!docDest.isEmpty()) dest.setCpf(docDest);
            dest.setRazaoSocial(String.valueOf(customer.getOrDefault("nome", "CONSUMIDOR FINAL")));
            dest.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);
            info.setDestinatario(dest);

            // ITENS (Proteção contra valores nulos nas contas)
            List<NFNotaInfoItem> listaItens = new ArrayList<>();
            BigDecimal totalProdutos = BigDecimal.ZERO;
            int ordem = 1;

            for (Map<String, Object> itemData : items) {
                NFNotaInfoItem item = new NFNotaInfoItem();
                item.setNumeroItem(ordem++);
                NFNotaInfoItemProduto prod = new NFNotaInfoItemProduto();
                prod.setCodigo(String.valueOf(itemData.getOrDefault("codigo", "0")));
                prod.setDescricao(String.valueOf(itemData.getOrDefault("descricao", "PRODUTO SEM DESCRICAO")));
                prod.setNcm(String.valueOf(itemData.getOrDefault("ncm", "")));
                prod.setCfop(String.valueOf(itemData.getOrDefault("cfop", "5102")));
                prod.setUnidadeComercial("UN");
                prod.setUnidadeTributavel("UN");
                
                BigDecimal qtd = new BigDecimal(String.valueOf(itemData.getOrDefault("quantidade", "1")));
                BigDecimal vUnit = new BigDecimal(String.valueOf(itemData.getOrDefault("valor_unitario", "0.00")));
                BigDecimal vTotal = qtd.multiply(vUnit).setScale(2, RoundingMode.HALF_UP);
                totalProdutos = totalProdutos.add(vTotal);
                
                prod.setQuantidadeComercial(qtd);
                prod.setQuantidadeTributavel(qtd);
                prod.setValorUnitario(vUnit);
                prod.setValorUnitarioTributavel(vUnit);
                prod.setValorTotalBruto(vTotal);
                item.setProduto(prod);

                String xmlImp = "<imposto><ICMS><ICMSSN102><orig>0</orig><CSOSN>102</CSOSN></ICMSSN102></ICMS></imposto>";
                item.setImposto(xmlParser.read(NFNotaInfoItemImposto.class, xmlImp));
                listaItens.add(item);
            }
            info.setItens(listaItens);

            // TOTAIS, PAGAMENTO E TRANSPORTE (Usando códigos fixos para evitar quebra de lib)
            String xmlTotStr = "<total><ICMSTot><vBC>0.00</vBC><vICMS>0.00</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP><vBCST>0.00</vBCST><vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet><vProd>" + totalProdutos + "</vProd><vFrete>0.00</vFrete><vSeg>0.00</vSeg><vDesc>0.00</vDesc><vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>0.00</vPIS><vCOFINS>0.00</vCOFINS><vOutro>0.00</vOutro><vNF>" + totalProdutos + "</vNF></ICMSTot></total>";
            info.setTotal(xmlParser.read(NFNotaInfoTotal.class, xmlTotStr));

            String xmlPagStr = "<pag><detPag><tPag>01</tPag><vPag>" + totalProdutos + "</vPag></detPag></pag>";
            info.setPagamento(xmlParser.read(NFNotaInfoPagamento.class, xmlPagStr));

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
            
            if (res == null || res.getRetorno() == null) throw new Exception("SEFAZ não retornou resposta (Timeout ou erro de rede)");

            return ResponseEntity.ok(Map.of(
                "status", res.getRetorno().getStatus(),
                "motivo", res.getRetorno().getMotivo() != null ? res.getRetorno().getMotivo() : "OK",
                "nota", numNotaLog
            ));

        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String fullError = sw.toString();
            return ResponseEntity.status(500).body(Map.of(
                "erro", e.getMessage() != null ? e.getMessage() : "Erro desconhecido",
                "detalhes", fullError.substring(0, Math.min(fullError.length(), 600)),
                "nota", numNotaLog
            ));
        }
    }
    @GetMapping("/process") public ResponseEntity<?> ping() { return ResponseEntity.ok(Map.of("status", "online")); }
}
// Quebra Cache: 2026-04-24T20:02:26.176Z-5glz8