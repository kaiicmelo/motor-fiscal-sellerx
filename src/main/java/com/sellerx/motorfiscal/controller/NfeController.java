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

    // Mapa estático sigla -> codigo IBGE da UF (resolve falta de valueOfSigla)
    private static final Map<String, String> UF_CODIGO = new HashMap<>();
    static {
        UF_CODIGO.put("AC","12"); UF_CODIGO.put("AL","27"); UF_CODIGO.put("AP","16");
        UF_CODIGO.put("AM","13"); UF_CODIGO.put("BA","29"); UF_CODIGO.put("CE","23");
        UF_CODIGO.put("DF","53"); UF_CODIGO.put("ES","32"); UF_CODIGO.put("GO","52");
        UF_CODIGO.put("MA","21"); UF_CODIGO.put("MT","51"); UF_CODIGO.put("MS","50");
        UF_CODIGO.put("MG","31"); UF_CODIGO.put("PA","15"); UF_CODIGO.put("PB","25");
        UF_CODIGO.put("PR","41"); UF_CODIGO.put("PE","26"); UF_CODIGO.put("PI","22");
        UF_CODIGO.put("RJ","33"); UF_CODIGO.put("RN","24"); UF_CODIGO.put("RS","43");
        UF_CODIGO.put("RO","11"); UF_CODIGO.put("RR","14"); UF_CODIGO.put("SC","42");
        UF_CODIGO.put("SP","35"); UF_CODIGO.put("SE","28"); UF_CODIGO.put("TO","17");
    }

    private static DFUnidadeFederativa ufBySigla(String sigla) {
        String codigo = UF_CODIGO.getOrDefault(sigla.toUpperCase(), "35");
        return DFUnidadeFederativa.valueOfCodigo(codigo);
    }

    @PostMapping("/process")
    public ResponseEntity<?> process(@RequestBody Map<String, Object> payload) {
        try {
            Map<String, Object> company  = (Map<String, Object>) payload.get("company");
            Map<String, Object> invoice  = (Map<String, Object>) payload.get("invoice");
            Map<String, Object> customer = (Map<String, Object>) payload.get("customer");
            List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

            String numeroNota = String.valueOf(invoice.getOrDefault("numero", "1"));
            if ("null".equals(numeroNota)) numeroNota = "1";

            // ====== CERTIFICADO ======
            URL url = new URL((String) company.get("certificate_file_uri"));
            String certPass = (String) company.get("certificate_password");
            byte[] pfx;
            try (InputStream in = url.openStream()) { pfx = in.readAllBytes(); }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pfx), certPass.toCharArray());

            // ====== UF dinamica ======
            String ufSigla = String.valueOf(company.getOrDefault("uf", "SP"));
            DFUnidadeFederativa ufEnum = ufBySigla(ufSigla);

            NFeConfig config = new NFeConfig() {
                @Override public DFUnidadeFederativa getCUF() { return ufEnum; }
                @Override public DFAmbiente getAmbiente() {
                    return "PRODUCAO".equals(company.get("ambiente")) ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO;
                }
                @Override public KeyStore getCertificadoKeyStore() { return ks; }
                @Override public String   getCertificadoSenha()    { return certPass; }
                @Override public KeyStore getCadeiaCertificadosKeyStore() { return ks; }
                @Override public String   getCadeiaCertificadosSenha()    { return certPass; }
            };

            Persister xmlParser = new Persister();
            NFNota nota = new NFNota();
            NFNotaInfo info = new NFNotaInfo();
            info.setVersao(new BigDecimal("4.00"));

            // ---- Identificacao ----
            NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
            ide.setUf(ufEnum);
            ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
            ide.setNaturezaOperacao(String.valueOf(invoice.getOrDefault("natureza_operacao", "VENDA")));
            ide.setModelo(DFModelo.NFE);
            ide.setSerie(String.valueOf(invoice.getOrDefault("serie", "1")));
            ide.setNumeroNota(numeroNota);
            ide.setDataHoraEmissao(ZonedDateTime.now());
            ide.setTipoEmissao(NFTipoEmissao.EMISSAO_NORMAL);

            String tipoOp = String.valueOf(invoice.getOrDefault("tipo_operacao", "1"));
            ide.setTipo("0".equals(tipoOp) ? NFTipo.ENTRADA : NFTipo.SAIDA);

            String idDest = String.valueOf(invoice.getOrDefault("id_destino", "1"));
            ide.setIdentificadorLocalDestinoOperacao(NFIdentificadorLocalDestinoOperacao.valueOfCodigo(idDest));

            ide.setCodigoMunicipio(String.valueOf(
                invoice.getOrDefault("codigo_municipio_fato_gerador",
                company.getOrDefault("codigo_municipio", "3516200"))));

            ide.setTipoImpressao(NFTipoImpressao.DANFE_NORMAL_RETRATO);
            ide.setFinalidade(NFFinalidade.NORMAL);
            ide.setOperacaoConsumidorFinal(NFOperacaoConsumidorFinal.SIM);
            ide.setIndicadorPresencaComprador(
                NFIndicadorPresencaComprador.valueOfCodigo(
                    String.valueOf(invoice.getOrDefault("indicador_presenca", "2"))));
            ide.setProgramaEmissor(NFProcessoEmissor.CONTRIBUINTE);
            ide.setVersaoEmissor("1.1.10");
            info.setIdentificacao(ide);

            // ---- Emitente ----
            NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
            emit.setCnpj(String.valueOf(company.get("cnpj")).replaceAll("[^0-9]", ""));
            emit.setRazaoSocial(String.valueOf(company.get("razao_social")));
            emit.setInscricaoEstadual(String.valueOf(company.get("inscricao_estadual")).replaceAll("[^0-9]", ""));
            emit.setRegimeTributario(NFRegimeTributario.SIMPLES_NACIONAL);

            Map<String, Object> endEmit = (Map<String, Object>) company.getOrDefault("endereco", new HashMap<>());
            NFEndereco endE = new NFEndereco();
            endE.setUf(ufBySigla(String.valueOf(endEmit.getOrDefault("uf", ufSigla))));
            endE.setCodigoMunicipio(String.valueOf(endEmit.getOrDefault("codigo_municipio", "3516200")));
            endE.setDescricaoMunicipio(String.valueOf(endEmit.getOrDefault("municipio", "FRANCA")));
            endE.setLogradouro(String.valueOf(endEmit.getOrDefault("logradouro", "RUA SEM NOME")));
            endE.setNumero(String.valueOf(endEmit.getOrDefault("numero", "S/N")));
            endE.setBairro(String.valueOf(endEmit.getOrDefault("bairro", "CENTRO")));
            endE.setCep(String.valueOf(endEmit.getOrDefault("cep", "14400000")).replaceAll("[^0-9]", ""));
            endE.setCodigoPais("1058");
            endE.setDescricaoPais("BRASIL");
            emit.setEndereco(endE);
            info.setEmitente(emit);

            // ---- Destinatario ----
            NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
            String doc = String.valueOf(customer.getOrDefault("documento", "")).replaceAll("[^0-9]", "");
            if (doc.length() > 11) dest.setCnpj(doc); else dest.setCpf(doc);
            dest.setRazaoSocial(String.valueOf(customer.get("nome")));
            dest.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);

            Map<String, Object> endDest = (Map<String, Object>) customer.getOrDefault("endereco", new HashMap<>());
            NFEndereco endD = new NFEndereco();
            endD.setUf(ufBySigla(String.valueOf(endDest.getOrDefault("uf", "SP"))));
            endD.setCodigoMunicipio(String.valueOf(endDest.getOrDefault("codigo_municipio", "3550308")));
            endD.setDescricaoMunicipio(String.valueOf(endDest.getOrDefault("municipio", "SAO PAULO")));
            endD.setLogradouro(String.valueOf(endDest.getOrDefault("logradouro", "RUA SEM NOME")));
            endD.setNumero(String.valueOf(endDest.getOrDefault("numero", "S/N")));
            endD.setBairro(String.valueOf(endDest.getOrDefault("bairro", "CENTRO")));
            endD.setCep(String.valueOf(endDest.getOrDefault("cep", "01001000")).replaceAll("[^0-9]", ""));
            endD.setCodigoPais("1058");
            endD.setDescricaoPais("BRASIL");
            dest.setEndereco(endD);
            info.setDestinatario(dest);

            // ---- Itens ----
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
                p.setUnidadeComercial("UN");
                p.setUnidadeTributavel("UN");

                BigDecimal q = new BigDecimal(String.valueOf(i.getOrDefault("quantidade", "1")).replace(",", "."));
                BigDecimal v = new BigDecimal(String.valueOf(i.getOrDefault("valor_unitario", "0")).replace(",", "."));
                BigDecimal t = q.multiply(v).setScale(2, RoundingMode.HALF_UP);
                totalProd = totalProd.add(t);

                p.setQuantidadeComercial(q);
                p.setQuantidadeTributavel(q);
                p.setValorUnitario(v);
                p.setValorUnitarioTributavel(v);
                p.setValorTotalBruto(t);
                item.setProduto(p);

                // Imposto via XML (compatibilidade com Fincatto 5.0.48)
                // origem=0 (Nacional) - parser do Fincatto resolve corretamente quando vem via XML
                String impostoXml =
                    "<imposto>" +
                      "<ICMS>" +
                        "<ICMSSN102>" +
                          "<orig>0</orig>" +
                          "<CSOSN>102</CSOSN>" +
                        "</ICMSSN102>" +
                      "</ICMS>" +
                      "<PIS>" +
                        "<PISOutr>" +
                          "<CST>49</CST>" +
                          "<vBC>0.00</vBC>" +
                          "<pPIS>0.00</pPIS>" +
                          "<vPIS>0.00</vPIS>" +
                        "</PISOutr>" +
                      "</PIS>" +
                      "<COFINS>" +
                        "<COFINSOutr>" +
                          "<CST>49</CST>" +
                          "<vBC>0.00</vBC>" +
                          "<pCOFINS>0.00</pCOFINS>" +
                          "<vCOFINS>0.00</vCOFINS>" +
                        "</COFINSOutr>" +
                      "</COFINS>" +
                    "</imposto>";

                NFNotaInfoItemImposto imp = xmlParser.read(NFNotaInfoItemImposto.class, impostoXml);
                item.setImposto(imp);
                lista.add(item);
            }
            info.setItens(lista);

            // ---- Totais via XML ----
            String vt = totalProd.setScale(2, RoundingMode.HALF_UP).toPlainString();
            String totalXml =
                "<total><ICMSTot>" +
                  "<vBC>0.00</vBC><vICMS>0.00</vICMS><vICMSDeson>0.00</vICMSDeson>" +
                  "<vFCP>0.00</vFCP><vBCST>0.00</vBCST><vST>0.00</vST>" +
                  "<vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet>" +
                  "<vProd>" + vt + "</vProd>" +
                  "<vFrete>0.00</vFrete><vSeg>0.00</vSeg><vDesc>0.00</vDesc>" +
                  "<vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol>" +
                  "<vPIS>0.00</vPIS><vCOFINS>0.00</vCOFINS><vOutro>0.00</vOutro>" +
                  "<vNF>" + vt + "</vNF>" +
                "</ICMSTot></total>";
            info.setTotal(xmlParser.read(NFNotaInfoTotal.class, totalXml));

            // ---- Pagamento via XML ----
            String pagXml = "<pag><detPag><tPag>01</tPag><vPag>" + vt + "</vPag></detPag></pag>";
            info.setPagamento(xmlParser.read(NFNotaInfoPagamento.class, pagXml));

            // ---- Transporte ----
            NFNotaInfoTransporte tr = new NFNotaInfoTransporte();
            tr.setModalidadeFrete(NFModalidadeFrete.valueOfCodigo("9"));
            info.setTransporte(tr);

            nota.setInfo(info);

            // ====== ENVIO ======
            WSFacade ws = new WSFacade(config);
            NFLoteEnvio lote = new NFLoteEnvio();
            lote.setNotas(Collections.singletonList(nota));
            lote.setIdLote("1");
            lote.setVersao("4.00");

            NFLoteEnvioRetornoDados resDados = ws.enviaLote(lote);
            NFLoteEnvioRetorno res = resDados.getRetorno();

            return ResponseEntity.ok(Map.of(
                "status", res.getStatus(),
                "motivo", res.getMotivo() != null ? res.getMotivo() : "OK"
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("erro", e.getMessage()));
        }
    }

    @GetMapping("/process")
    public ResponseEntity<?> ping() {
        return ResponseEntity.ok(Map.of("status", "online", "version", "1.1.10"));
    }
}