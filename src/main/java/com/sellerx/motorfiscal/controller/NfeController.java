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
        try {
            Map<String, Object> company = (Map<String, Object>) payload.get("company");
            Map<String, Object> invoice = (Map<String, Object>) payload.get("invoice");
            Map<String, Object> customer = (Map<String, Object>) payload.get("customer");
            List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

            String numeroNota = (invoice.get("numero") == null || String.valueOf(invoice.get("numero")).equals("null")) ? "1" : String.valueOf(invoice.get("numero"));

            URL url = new URL((String) company.get("certificate_file_uri"));
            String certPass = (String) company.get("certificate_password");
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

            NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
            ide.setUf(config.getCUF());
            ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
            ide.setNaturezaOperacao(String.valueOf(invoice.getOrDefault("natureza_operacao", "VENDA")));
            ide.setModelo(DFModelo.NFE);
            ide.setSerie(String.valueOf(invoice.getOrDefault("serie", "1")));
            ide.setNumeroNota(numeroNota);
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
            ide.setVersaoEmissor("1.1.7");
            info.setIdentificacao(ide);

            NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
            emit.setCnpj(String.valueOf(company.get("cnpj")).replaceAll("[^0-9]", ""));
            emit.setRazaoSocial(String.valueOf(company.get("razao_social")));
            emit.setInscricaoEstadual(String.valueOf(company.get("inscricao_estadual")));
            emit.setRegimeTributario(NFRegimeTributario.SIMPLES_NACIONAL);
            info.setEmitente(emit);

            NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
            String doc = String.valueOf(customer.getOrDefault("documento", "")).replaceAll("[^0-9]", "");
            if(doc.length() > 11) dest.setCnpj(doc); else dest.setCpf(doc);
            dest.setRazaoSocial(String.valueOf(customer.get("nome")));
            dest.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);
            info.setDestinatario(dest);

            List<NFNotaInfoItem> lista = new ArrayList<>();
            BigDecimal totalProd = BigDecimal.ZERO;
            int o = 1;
            for (Map<String, Object> i : items) {
                NFNotaInfoItem item = new NFNotaInfoItem();
                item.setNumeroItem(o++);
                NFNotaInfoItemProduto p = new NFNotaInfoItemProduto();
                p.setCodigo(String.valueOf(i.getOrDefault("codigo", "1")));
                p.setDescricao(String.valueOf(i.getOrDefault("descricao", "PRODUTO")));
                p.setNcm(String.valueOf(i.getOrDefault("ncm", "00000000")));
                p.setCfop(String.valueOf(i.getOrDefault("cfop", "5102")));
                p.setUnidadeComercial("UN"); p.setUnidadeTributavel("UN");
                BigDecimal q = new BigDecimal(String.valueOf(i.getOrDefault("quantidade", "1")).replace(",", "."));
                BigDecimal v = new BigDecimal(String.valueOf(i.getOrDefault("valor_unitario", "0")).replace(",", "."));
                BigDecimal t = q.multiply(v).setScale(2, RoundingMode.HALF_UP);
                totalProd = totalProd.add(t);
                p.setQuantidadeComercial(q); p.setQuantidadeTributavel(q);
                p.setValorUnitario(v); p.setValorUnitarioTributavel(v);
                p.setValorTotalBruto(t);
                item.setProduto(p);
                
                // MÁGICA: Bypassa totalmente a instanciacao das classes CSOSN injetando o XML direto
                NFNotaInfoItemImposto imp = xmlParser.read(NFNotaInfoItemImposto.class, "<imposto><ICMS><ICMSSN102><orig>0</orig><CSOSN>102</CSOSN></ICMSSN102></ICMS></imposto>");
                item.setImposto(imp); 
                lista.add(item);
            }
            info.setItens(lista);
            String vt = totalProd.setScale(2, RoundingMode.HALF_UP).toPlainString();
            info.setTotal(xmlParser.read(NFNotaInfoTotal.class, "<total><ICMSTot><vBC>0.00</vBC><vICMS>0.00</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP><vBCST>0.00</vBCST><vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet><vProd>"+vt+"</vProd><vFrete>0.00</vFrete><vSeg>0.00</vSeg><vDesc>0.00</vDesc><vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>0.00</vPIS><vCOFINS>0.00</vCOFINS><vOutro>0.00</vOutro><vNF>"+vt+"</vNF></ICMSTot></total>"));
            info.setPagamento(xmlParser.read(NFNotaInfoPagamento.class, "<pag><detPag><tPag>01</tPag><vPag>"+vt+"</vPag></detPag></pag>"));
            NFNotaInfoTransporte tr = new NFNotaInfoTransporte(); tr.setModalidadeFrete(NFModalidadeFrete.valueOfCodigo("9"));
            info.setTransporte(tr); nota.setInfo(info);
            
            WSFacade ws = new WSFacade(config);
            NFLoteEnvio l = new NFLoteEnvio(); l.setNotas(Collections.singletonList(nota)); l.setIdLote("1"); l.setVersao("4.00");
            NFLoteEnvioRetornoDados resDados = ws.enviaLote(l);
            NFLoteEnvioRetorno res = resDados.getRetorno();
            return ResponseEntity.ok(Map.of("status", res.getStatus(), "motivo", res.getMotivo() != null ? res.getMotivo() : "OK"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("erro", e.getMessage()));
        }
    }
    @GetMapping("/process") public ResponseEntity<?> ping() { return ResponseEntity.ok(Map.of("status", "online")); }
}