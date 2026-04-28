package com.sellerx.motorfiscal.controller;

import com.fincatto.documentofiscal.DFAmbiente;
import com.fincatto.documentofiscal.DFModelo;
import com.fincatto.documentofiscal.DFPais;
import com.fincatto.documentofiscal.DFUnidadeFederativa;
import com.fincatto.documentofiscal.nfe.NFTipoEmissao;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.nfe400.classes.*;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.*;
import com.fincatto.documentofiscal.nfe400.classes.nota.*;
import com.fincatto.documentofiscal.nfe400.webservices.WSFacade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.security.KeyStore;
import java.time.ZonedDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/nfe")
public class NfeController {

    @GetMapping("/")
    public ResponseEntity<Map<String, String>> root() {
        Map<String, String> body = new HashMap<>();
        body.put("status", "ok");
        body.put("service", "motor-fiscal");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> body = new HashMap<>();
        body.put("status", "UP");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/process")
    @SuppressWarnings("unchecked")
    public Map<String, Object> process(@RequestBody Map<String, Object> payload) {
        Map<String, Object> response = new HashMap<>();
        try {
            String action = (String) payload.get("action");
            Map<String, Object> companyData  = (Map<String, Object>) payload.get("company");
            Map<String, Object> invoiceData  = (Map<String, Object>) payload.get("invoice");
            Map<String, Object> emitenteData = (Map<String, Object>) payload.get("emitente");
            Map<String, Object> customerData = (Map<String, Object>) payload.get("customer");
            List<Map<String, Object>> itemsData = (List<Map<String, Object>>) payload.get("items");
            Map<String, Object> totaisData    = (Map<String, Object>) payload.get("totais");
            Map<String, Object> pagamentoData = (Map<String, Object>) payload.get("pagamento");

            NFeConfig config = buildConfig(companyData);

            NFNota nota = new NFNota();
            NFNotaInfo info = new NFNotaInfo();
            info.setVersao(new BigDecimal("4.00"));

            // IDENTIFICAÇÃO
            NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
            String ufEmit = (String) ((Map<String, Object>) emitenteData.get("endereco")).get("uf");
            ide.setUf(DFUnidadeFederativa.valueOfCodigo(getUfCodigo(ufEmit)));
            ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
            ide.setNaturezaOperacao((String) invoiceData.getOrDefault("natureza_operacao", "Venda"));
            ide.setModelo(DFModelo.NFE);
            ide.setSerie((String) invoiceData.get("serie"));
            ide.setNumeroNota(String.format("%09d", Integer.parseInt((String) invoiceData.get("numero"))));
            ide.setDataHoraEmissao(ZonedDateTime.now());
            ide.setTipo(NFTipo.SAIDA);
            ide.setIdentificadorLocalDestinoOperacao(
                NFIdentificadorLocalDestinoOperacao.valueOfCodigo(
                    (String) invoiceData.getOrDefault("id_destino", "1")));
            ide.setCodigoMunicipio((String) invoiceData.get("codigo_municipio_fato_gerador"));
            ide.setTipoImpressao(NFTipoImpressao.DANFE_NORMAL_RETRATO);
            ide.setTipoEmissao(NFTipoEmissao.EMISSAO_NORMAL);
            ide.setAmbiente(config.getAmbiente());
            ide.setFinalidade(NFFinalidade.NORMAL);
            ide.setOperacaoConsumidorFinal(NFOperacaoConsumidorFinal.NAO);
            ide.setIndicadorPresencaComprador(NFIndicadorPresencaComprador.NAO_APLICA);
            ide.setProgramaEmissor(NFProcessoEmissor.CONTRIBUINTE);
            ide.setVersaoEmissor("1.0");
            info.setIdentificacao(ide);

            info.setEmitente(buildEmitente(emitenteData));
            info.setDestinatario(buildDestinatario(customerData));

            List<NFNotaInfoItem> itens = new ArrayList<>();
            for (int i = 0; i < itemsData.size(); i++) {
                itens.add(buildItem(itemsData.get(i), i + 1));
            }
            info.setItens(itens);

            info.setTotal(buildTotal(totaisData));

            NFNotaInfoTransporte transp = new NFNotaInfoTransporte();
            transp.setModalidadeFrete(NFModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE);
            info.setTransporte(transp);

            info.setPagamento(buildPagamento(pagamentoData));

            nota.setInfo(info);

            WSFacade ws = new WSFacade(config);

            if ("emitir".equals(action)) {
                NFLoteEnvio lote = new NFLoteEnvio();
                lote.setIdLote("1");
                lote.setIndicadorProcessamento(NFLoteIndicadorProcessamento.PROCESSAMENTO_SINCRONO);
                lote.setNotas(Collections.singletonList(nota));
                lote.setVersao("4.00");

                NFLoteEnvioRetornoDados retornoDados = ws.enviaLote(lote);
                NFLoteEnvioRetorno r = retornoDados.getRetorno();

                if (r.getProtocoloInfo() != null) {
                    response.put("status", "sucesso");
                    response.put("nfe_key", r.getProtocoloInfo().getChave());
                    response.put("protocolo", r.getProtocoloInfo().getNumeroProtocolo());
                    response.put("cStat", r.getStatus());
                    response.put("xMotivo", r.getMotivo());
                } else {
                    response.put("status", "erro");
                    response.put("erro", r.getMotivo());
                    response.put("cStat", r.getStatus());
                }
            }

            return response;
        } catch (Exception e) {
            e.printStackTrace();
            response.put("status", "erro");
            response.put("erro", e.getMessage());
            return response;
        }
    }

    // ============ CONFIG (carrega .pfx da URL assinada) ============
    private NFeConfig buildConfig(Map<String, Object> data) throws Exception {
        final String certUrl = (String) data.get("certificate_file_uri");
        final String certPwd = (String) data.get("certificate_password");
        final String ambStr  = (String) data.getOrDefault("ambiente", "HOMOLOGACAO");
        final String ufStr   = (String) data.getOrDefault("uf", "SP");

        final byte[] pfxBytes;
        try (InputStream in = new URL(certUrl).openStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            pfxBytes = baos.toByteArray();
        }

        final KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (ByteArrayInputStream bis = new ByteArrayInputStream(pfxBytes)) {
            keyStore.load(bis, certPwd.toCharArray());
        }

        final DFAmbiente ambiente = "PRODUCAO".equalsIgnoreCase(ambStr)
            ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO;
        final DFUnidadeFederativa uf = DFUnidadeFederativa.valueOfCodigo(getUfCodigo(ufStr));

        return new NFeConfig() {
            @Override public DFAmbiente getAmbiente() { return ambiente; }
            @Override public DFUnidadeFederativa getCUF() { return uf; }
            @Override public String getCertificadoSenha() { return certPwd; }
            @Override public KeyStore getCertificadoKeyStore() { return keyStore; }
            @Override public String getCadeiaCertificadosSenha() { return "changeit"; }
            @Override public KeyStore getCadeiaCertificadosKeyStore() {
                try {
                    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                    String javaHome = System.getProperty("java.home");
                    try (FileInputStream fis = new FileInputStream(javaHome + "/lib/security/cacerts")) {
                        ks.load(fis, "changeit".toCharArray());
                    }
                    return ks;
                } catch (Exception e) {
                    throw new RuntimeException("Erro ao carregar cacerts", e);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private NFNotaInfoEmitente buildEmitente(Map<String, Object> e) {
        NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
        emit.setCnpj((String) e.get("cnpj"));
        emit.setRazaoSocial((String) e.get("razao_social"));
        emit.setInscricaoEstadual((String) e.get("inscricao_estadual"));
        emit.setRegimeTributario(NFRegimeTributario.SIMPLES_NACIONAL);

        Map<String, Object> end = (Map<String, Object>) e.get("endereco");
        NFEndereco endereco = new NFEndereco();
        endereco.setLogradouro(safe((String) end.get("logradouro"), "ENDERECO"));
        endereco.setNumero("S/N");
        endereco.setBairro("Centro");
        endereco.setCodigoMunicipio((String) end.get("codigo_municipio"));
        endereco.setDescricaoMunicipio("CIDADE");
        endereco.setUf(DFUnidadeFederativa.valueOfCodigo(getUfCodigo((String) end.get("uf"))));
        endereco.setCep("00000000");
        endereco.setCodigoPais(DFPais.BRASIL);
        endereco.setDescricaoPais("BRASIL");
        emit.setEndereco(endereco);
        return emit;
    }

    @SuppressWarnings("unchecked")
    private NFNotaInfoDestinatario buildDestinatario(Map<String, Object> c) {
        NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
        String doc = (String) c.get("documento");
        if (doc != null && doc.length() == 14) dest.setCnpj(doc);
        else if (doc != null) dest.setCpf(doc);
        dest.setRazaoSocial((String) c.get("nome"));
        dest.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);

        Map<String, Object> end = (Map<String, Object>) c.get("endereco");
        NFEndereco endereco = new NFEndereco();
        endereco.setLogradouro(safe((String) end.get("logradouro"), "ENDERECO"));
        endereco.setNumero("S/N");
        endereco.setBairro("Centro");
        endereco.setCodigoMunicipio((String) end.get("codigo_municipio"));
        endereco.setDescricaoMunicipio("CIDADE");
        endereco.setUf(DFUnidadeFederativa.valueOfCodigo(getUfCodigo((String) end.get("uf"))));
        String cep = (String) end.get("cep");
        if (cep != null && cep.length() == 8) endereco.setCep(cep);
        endereco.setCodigoPais(DFPais.BRASIL);
        endereco.setDescricaoPais("BRASIL");
        dest.setEndereco(endereco);
        return dest;
    }

    @SuppressWarnings("unchecked")
    private NFNotaInfoItem buildItem(Map<String, Object> data, int numero) {
        NFNotaInfoItem item = new NFNotaInfoItem();
        item.setNumeroItem(numero);

        NFNotaInfoItemProduto prod = new NFNotaInfoItemProduto();
        prod.setCodigo((String) data.get("codigo"));
        prod.setDescricao((String) data.get("descricao"));
        prod.setNcm((String) data.get("ncm"));
        prod.setCfop((String) data.get("cfop"));
        prod.setUnidadeComercial("UN");
        prod.setQuantidadeComercial(toBD(data.get("quantidade")));
        prod.setValorUnitario(toBD(data.get("valor_unitario")));
        prod.setValorTotalBruto(toBD(data.get("valor_total")));
        prod.setUnidadeTributavel("UN");
        prod.setQuantidadeTributavel(toBD(data.get("quantidade")));
        prod.setValorUnitarioTributavel(toBD(data.get("valor_unitario")));
        prod.setCompoeValorNota(NFProdutoCompoeValorNota.SIM);
        item.setProduto(prod);

        NFNotaInfoItemImposto imposto = new NFNotaInfoItemImposto();

        Map<String, Object> icmsData = (Map<String, Object>) data.get("icms");
        String csosn = String.valueOf(icmsData.getOrDefault("cst", "102")).trim();
        NFOrigem origem = NFOrigem.valueOfCodigo(icmsData.getOrDefault("origem", "0").toString());

        NFNotaInfoItemImpostoICMS icms = new NFNotaInfoItemImpostoICMS();

        switch (csosn) {
            case "101": {
                NFNotaInfoItemImpostoICMSSN101 sn = new NFNotaInfoItemImpostoICMSSN101();
                sn.setOrigem(origem);
                sn.setValorCreditoICMSSN(BigDecimal.ZERO);
                icms.setIcmssn101(sn);
                break;
            }
            case "102":
            case "103":
            case "300":
            case "400": {
                NFNotaInfoItemImpostoICMSSN102 sn = new NFNotaInfoItemImpostoICMSSN102();
                sn.setOrigem(origem);
                icms.setIcmssn102(sn);
                break;
            }
            case "201": {
                NFNotaInfoItemImpostoICMSSN201 sn = new NFNotaInfoItemImpostoICMSSN201();
                sn.setOrigem(origem);
                sn.setModalidadeBCICMSST(NFNotaInfoItemModalidadeBCICMSST.LISTA_NEGATIVA);
                sn.setValorBCICMSST(BigDecimal.ZERO);
                sn.setValorICMSST(BigDecimal.ZERO);
                sn.setValorCreditoICMSSN(BigDecimal.ZERO);
                icms.setIcmssn201(sn);
                break;
            }
            case "202":
            case "203": {
                NFNotaInfoItemImpostoICMSSN202 sn = new NFNotaInfoItemImpostoICMSSN202();
                sn.setOrigem(origem);
                sn.setModalidadeBCICMSST(NFNotaInfoItemModalidadeBCICMSST.LISTA_NEGATIVA);
                sn.setValorBCICMSST(BigDecimal.ZERO);
                sn.setValorICMSST(BigDecimal.ZERO);
                icms.setIcmssn202(sn);
                break;
            }
            case "500": {
                NFNotaInfoItemImpostoICMSSN500 sn = new NFNotaInfoItemImpostoICMSSN500();
                sn.setOrigem(origem);
                icms.setIcmssn500(sn);
                break;
            }
            case "900": {
                NFNotaInfoItemImpostoICMSSN900 sn = new NFNotaInfoItemImpostoICMSSN900();
                sn.setOrigem(origem);
                sn.setModalidadeBCICMS(NFNotaInfoItemModalidadeBCICMS.VALOR_OPERACAO);
                sn.setValorBCICMS(BigDecimal.ZERO);
                sn.setValorICMS(BigDecimal.ZERO);
                icms.setIcmssn900(sn);
                break;
            }
            default: {
                NFNotaInfoItemImpostoICMSSN102 sn = new NFNotaInfoItemImpostoICMSSN102();
                sn.setOrigem(origem);
                icms.setIcmssn102(sn);
            }
        }
        imposto.setIcms(icms);

        NFNotaInfoItemImpostoPIS pis = new NFNotaInfoItemImpostoPIS();
        NFNotaInfoItemImpostoPISOutrasOperacoes pisOutr = new NFNotaInfoItemImpostoPISOutrasOperacoes();
        pisOutr.setSituacaoTributaria(NFNotaInfoSituacaoTributariaPIS.valueOfCodigo("49"));
        pisOutr.setValorBaseCalculo(BigDecimal.ZERO);
        // Tente usar setPercentualAliquota para PIS, já que o compilador não reclamou dele neste último log
        pisOutr.setPercentualAliquota(BigDecimal.ZERO); 
        pisOutr.setValorTributo(BigDecimal.ZERO);
        pis.setOutrasOperacoes(pisOutr);
        imposto.setPis(pis);

        NFNotaInfoItemImpostoCOFINS cofins = new NFNotaInfoItemImpostoCOFINS();
        NFNotaInfoItemImpostoCOFINSOutrasOperacoes cofinsOutr = new NFNotaInfoItemImpostoCOFINSOutrasOperacoes();
        cofinsOutr.setSituacaoTributaria(NFNotaInfoSituacaoTributariaCOFINS.valueOfCodigo("49"));
        cofinsOutr.setValorBaseCalculo(BigDecimal.ZERO);
        // CORREÇÃO CRÍTICA PARA O COFINS: A biblioteca Fincatto 4.0.32 usa setPercentualCOFINS para Outras Operações
        cofinsOutr.setPercentualCOFINS(BigDecimal.ZERO); 
        cofinsOutr.setValorCOFINS(BigDecimal.ZERO);
        cofins.setOutrasOperacoes(cofinsOutr);
        imposto.setCofins(cofins);

        item.setImposto(imposto);
        return item;
    }

    private NFNotaInfoTotal buildTotal(Map<String, Object> data) {
        NFNotaInfoTotal total = new NFNotaInfoTotal();
        NFNotaInfoICMSTotal icmsTot = new NFNotaInfoICMSTotal();
        icmsTot.setBaseCalculoICMS(toBD(data.getOrDefault("vBC", "0")));
        icmsTot.setValorTotalICMS(toBD(data.getOrDefault("vICMS", "0")));
        icmsTot.setValorICMSDesonerado(BigDecimal.ZERO);
        icmsTot.setBaseCalculoICMSST(BigDecimal.ZERO);
        icmsTot.setValorTotalICMSST(BigDecimal.ZERO);
        icmsTot.setValorTotalDosProdutosServicos(toBD(data.getOrDefault("vProd", "0")));
        icmsTot.setValorTotalFrete(toBD(data.getOrDefault("vFrete", "0")));
        icmsTot.setValorTotalSeguro(BigDecimal.ZERO);
        icmsTot.setValorTotalDesconto(toBD(data.getOrDefault("vDesc", "0")));
        icmsTot.setValorTotalII(BigDecimal.ZERO);
        icmsTot.setValorTotalIPI(BigDecimal.ZERO);
        icmsTot.setValorTotalIPIDevolvido(BigDecimal.ZERO);
        icmsTot.setValorPIS(BigDecimal.ZERO);
        icmsTot.setValorCOFINS(BigDecimal.ZERO);
        icmsTot.setOutrasDespesasAcessorias(toBD(data.getOrDefault("vOutro", "0")));
        icmsTot.setValorTotalNFe(toBD(data.getOrDefault("vNF", "0")));
        icmsTot.setValorTotalTributos(toBD(data.getOrDefault("vTotTrib", "0")));
        icmsTot.setValorTotalFundoCombatePobreza(BigDecimal.ZERO);
        icmsTot.setValorTotalFundoCombatePobrezaST(BigDecimal.ZERO);
        icmsTot.setValorTotalFundoCombatePobrezaSTRetido(BigDecimal.ZERO);
        total.setIcmsTotal(icmsTot);
        return total;
    }

    private NFNotaInfoPagamento buildPagamento(Map<String, Object> data) {
        NFNotaInfoPagamento pag = new NFNotaInfoPagamento();
        NFNotaInfoFormaPagamento forma = new NFNotaInfoFormaPagamento();
        forma.setIndicadorFormaPagamento(NFIndicadorFormaPagamento.A_VISTA);
        forma.setMeioPagamento(NFMeioPagamento.valueOfCodigo((String) data.getOrDefault("tipo", "99")));
        forma.setValorPagamento(toBD(data.get("valor")));
        pag.setDetalhamentoFormasPagamento(Collections.singletonList(forma));
        return pag;
    }

    private BigDecimal toBD(Object v) {
        if (v == null) return BigDecimal.ZERO;
        return new BigDecimal(v.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private String safe(String s, String fallback) {
        return (s == null || s.trim().isEmpty()) ? fallback : s;
    }

    private String getUfCodigo(String uf) {
        if (uf == null) return "35";
        Map<String, String> m = new HashMap<>();
        m.put("AC","12"); m.put("AL","27"); m.put("AP","16"); m.put("AM","13");
        m.put("BA","29"); m.put("CE","23"); m.put("DF","53"); m.put("ES","32");
        m.put("GO","52"); m.put("MA","21"); m.put("MT","51"); m.put("MS","50");
        m.put("MG","31"); m.put("PA","15"); m.put("PB","25"); m.put("PR","41");
        m.put("PE","26"); m.put("PI","22"); m.put("RJ","33"); m.put("RN","24");
        m.put("RS","43"); m.put("RO","11"); m.put("RR","14"); m.put("SC","42");
        m.put("SP","35"); m.put("SE","28"); m.put("TO","17");
        return m.getOrDefault(uf.toUpperCase(), "35");
    }
}