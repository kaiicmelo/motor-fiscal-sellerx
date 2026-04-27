package com.sellerx.motorfiscal.controller;

import com.fincatto.documentofiscal.DFAmbiente;
import com.fincatto.documentofiscal.DFModelo;
import com.fincatto.documentofiscal.DFUnidadeFederativa;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.nfe400.NFeFactory;
import com.fincatto.documentofiscal.nfe400.classes.*;
import com.fincatto.documentofiscal.nfe400.classes.nota.*;
import com.fincatto.documentofiscal.nfe400.webservices.WSFacade;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/nfe")
public class NfeController {

    @PostMapping("/process")
    public Map<String, Object> process(@RequestBody Map<String, Object> payload) throws Exception {
        Map<String, Object> response = new HashMap<>();
        try {
            String action = (String) payload.get("action");
            Map<String, Object> companyData = (Map<String, Object>) payload.get("company");
            Map<String, Object> invoiceData = (Map<String, Object>) payload.get("invoice");
            Map<String, Object> emitenteData = (Map<String, Object>) payload.get("emitente");
            Map<String, Object> customerData = (Map<String, Object>) payload.get("customer");
            List<Map<String, Object>> itemsData = (List<Map<String, Object>>) payload.get("items");
            Map<String, Object> totaisData = (Map<String, Object>) payload.get("totais");
            Map<String, Object> pagamentoData = (Map<String, Object>) payload.get("pagamento");

            // ===== 1. CONFIGURAÇÃO =====
            NFeConfig config = buildConfig(companyData);

            // ===== 2. MONTAR NF-e PROGRAMATICAMENTE =====
            NFNota nota = new NFNota();
            NFNotaInfo info = new NFNotaInfo();
            info.setVersao("4.00");

            // ----- Identificação -----
            NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
            ide.setUf(DFUnidadeFederativa.valueOfCodigo(getUfCodigo((String) emitenteData.get("uf"))));
            ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
            ide.setNaturezaOperacao((String) invoiceData.getOrDefault("natureza_operacao", "Venda"));
            ide.setModelo(DFModelo.NFE);
            ide.setSerie(Integer.parseInt((String) invoiceData.get("serie")));
            ide.setNumeroNota(Integer.parseInt((String) invoiceData.get("numero")));
            ide.setDataHoraEmissao(ZonedDateTime.now());
            ide.setTipo(NFTipo.SAIDA);
            ide.setIdentificadorLocalDestinoOperacao(
                NFIdentificadorLocalDestinoOperacao.valueOfCodigo(
                    (String) invoiceData.getOrDefault("id_destino", "1")));
            ide.setCodigoMunicipio((String) invoiceData.get("codigo_municipio_fato_gerador"));
            ide.setFormatoImpressaoDANFE(NFFormatoImpressaoDANFE.DANFE_NORMAL_RETRATO);
            ide.setTipoEmissao(NFTipoEmissao.EMISSAO_NORMAL);
            ide.setIdentificadorAmbiente(config.getAmbiente());
            ide.setFinalidadeEmissao(NFFinalidade.NORMAL);
            ide.setConsumidorOperacao(NFOperacaoConsumidorFinal.NAO);
            ide.setPresencaComprador(NFPresencaComprador.NAO_SE_APLICA);
            ide.setProcessoEmissao(NFProcessoEmissao.APLICATIVO_CONTRIBUINTE);
            ide.setVersaoEmissor("1.0");
            info.setIdentificacao(ide);

            // ----- Emitente -----
            info.setEmitente(buildEmitente(emitenteData, companyData));

            // ----- Destinatário -----
            info.setDestinatario(buildDestinatario(customerData));

            // ----- Itens -----
            List<NFNotaInfoItem> itens = new ArrayList<>();
            for (int i = 0; i < itemsData.size(); i++) {
                itens.add(buildItem(itemsData.get(i), i + 1));
            }
            info.setItens(itens);

            // ----- Totais -----
            info.setTotal(buildTotal(totaisData));

            // ----- Transporte -----
            NFNotaInfoTransporte transp = new NFNotaInfoTransporte();
            transp.setModalidadeFrete(NFModalidadeFrete.SEM_FRETE);
            info.setTransporte(transp);

            // ----- Pagamento -----
            info.setPagamento(buildPagamento(pagamentoData));

            nota.setInfo(info);

            // ===== 3. ASSINAR / TRANSMITIR =====
            WSFacade ws = new WSFacade(config);

            if ("emitir".equals(action)) {
                NFNota notaAssinada = ws.getAssinaturaService().assinarNota(nota);
                NFLoteEnvio lote = new NFLoteEnvio();
                lote.setIdLote("1");
                lote.setIndicadorProcessamento(NFLoteIndicadorProcessamento.PROCESSAMENTO_SINCRONO);
                lote.setNotas(Collections.singletonList(notaAssinada));

                NFLoteEnvioRetornoDados retorno = ws.getNotaEnviaService().enviaNota(lote);
                NFLoteEnvioRetorno r = retorno.getRetorno();

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

    // ===== HELPERS =====

    private NFeConfig buildConfig(Map<String, Object> data) {
        // Sua impl atual de NFeConfig com certificado .pfx baixado
        // Mantenha o que já tem aqui — só não mexer no parse de XML
        return new SuaNFeConfigImpl(data); // ajuste conforme seu projeto
    }

    private NFNotaInfoEmitente buildEmitente(Map<String, Object> e, Map<String, Object> c) {
        NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
        emit.setCnpj((String) e.get("cnpj"));
        emit.setRazaoSocial((String) e.get("razao_social"));
        emit.setInscricaoEstadual((String) e.get("inscricao_estadual"));
        emit.setRegimeTributario(NFRegimeTributario.SIMPLES_NACIONAL);

        Map<String, Object> end = (Map<String, Object>) e.get("endereco");
        NFEndereco endereco = new NFEndereco();
        endereco.setLogradouro((String) end.get("logradouro"));
        endereco.setNumero("S/N");
        endereco.setBairro("Centro");
        endereco.setCodigoMunicipio((String) end.get("codigo_municipio"));
        endereco.setDescricaoMunicipio("Cidade");
        endereco.setUf(DFUnidadeFederativa.valueOfCodigo(getUfCodigo((String) end.get("uf"))));
        endereco.setCep("00000000");
        endereco.setCodigoPais("1058");
        endereco.setDescricaoPais("BRASIL");
        emit.setEndereco(endereco);
        return emit;
    }

    private NFNotaInfoDestinatario buildDestinatario(Map<String, Object> c) {
        NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
        String doc = (String) c.get("documento");
        if (doc.length() == 14) dest.setCnpj(doc);
        else dest.setCpf(doc);
        dest.setRazaoSocial((String) c.get("nome"));
        dest.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);

        Map<String, Object> end = (Map<String, Object>) c.get("endereco");
        NFEndereco endereco = new NFEndereco();
        endereco.setLogradouro((String) end.get("logradouro"));
        endereco.setNumero("S/N");
        endereco.setBairro("Centro");
        endereco.setCodigoMunicipio((String) end.get("codigo_municipio"));
        endereco.setDescricaoMunicipio("Cidade");
        endereco.setUf(DFUnidadeFederativa.valueOfCodigo(getUfCodigo((String) end.get("uf"))));
        endereco.setCep((String) end.get("cep"));
        endereco.setCodigoPais("1058");
        endereco.setDescricaoPais("BRASIL");
        dest.setEndereco(endereco);
        return dest;
    }

    private NFNotaInfoItem buildItem(Map<String, Object> data, int numero) {
        NFNotaInfoItem item = new NFNotaInfoItem();
        item.setNumeroItem(numero);

        // Produto
        NFNotaInfoItemProduto prod = new NFNotaInfoItemProduto();
        prod.setCodigo((String) data.get("codigo"));
        prod.setDescricao((String) data.get("descricao"));
        prod.setNcm((String) data.get("ncm"));
        prod.setCfop((String) data.get("cfop"));
        prod.setUnidadeComercial("UN");
        prod.setQuantidadeComercial(new BigDecimal(data.get("quantidade").toString()));
        prod.setValorUnitario(new BigDecimal(data.get("valor_unitario").toString()));
        prod.setValorTotalBruto(new BigDecimal(data.get("valor_total").toString()));
        prod.setUnidadeTributavel("UN");
        prod.setQuantidadeTributavel(new BigDecimal(data.get("quantidade").toString()));
        prod.setValorUnitarioTributavel(new BigDecimal(data.get("valor_unitario").toString()));
        prod.setProdutoCompoeValorNota(true);
        item.setProduto(prod);

        // Imposto
        NFNotaInfoItemImposto imposto = new NFNotaInfoItemImposto();

        // ---- ICMS SN 102 (CSOSN 102) ----
        Map<String, Object> icmsData = (Map<String, Object>) data.get("icms");
        String csosn = (String) icmsData.getOrDefault("cst", "102");

        NFNotaInfoItemImpostoICMS icms = new NFNotaInfoItemImpostoICMS();
        NFNotaInfoItemImpostoICMSSN102 icmsSN = new NFNotaInfoItemImpostoICMSSN102();
        // 🎯 USO CORRETO: valueOfCodigo() em vez de Enum.valueOf()
        icmsSN.setOrigem(NFOrigem.valueOfCodigo(
            icmsData.getOrDefault("origem", "0").toString()));
        icmsSN.setSituacaoOperacaoSN(
            NFNotaSituacaoOperacionalSimplesNacional.valueOfCodigo(csosn));
        icms.setIcmssn102(icmsSN);
        imposto.setIcms(icms);

        // ---- PIS CST 49 (Outras) ----
        NFNotaInfoItemImpostoPIS pis = new NFNotaInfoItemImpostoPIS();
        NFNotaInfoItemImpostoPISOutrasOperacoes pisOutr = new NFNotaInfoItemImpostoPISOutrasOperacoes();
        pisOutr.setSituacaoTributaria(
            NFNotaInfoSituacaoTributariaPIS.valueOfCodigo("49"));
        pisOutr.setValorBaseCalculo(BigDecimal.ZERO);
        pisOutr.setPercentualAliquota(BigDecimal.ZERO);
        pisOutr.setValorTributo(BigDecimal.ZERO);
        pis.setPisOutrasOperacoes(pisOutr);
        imposto.setPis(pis);

        // ---- COFINS CST 49 ----
        NFNotaInfoItemImpostoCOFINS cofins = new NFNotaInfoItemImpostoCOFINS();
        NFNotaInfoItemImpostoCOFINSOutrasOperacoes cofinsOutr = new NFNotaInfoItemImpostoCOFINSOutrasOperacoes();
        cofinsOutr.setSituacaoTributaria(
            NFNotaInfoSituacaoTributariaCOFINS.valueOfCodigo("49"));
        cofinsOutr.setValorBaseCalculo(BigDecimal.ZERO);
        cofinsOutr.setPercentualAliquota(BigDecimal.ZERO);
        cofinsOutr.setValorTributo(BigDecimal.ZERO);
        cofins.setCofinsOutrasOperacoes(cofinsOutr);
        imposto.setCofins(cofins);

        item.setImposto(imposto);
        return item;
    }

    private NFNotaInfoTotal buildTotal(Map<String, Object> data) {
        NFNotaInfoTotal total = new NFNotaInfoTotal();
        NFNotaInfoICMSTotal icmsTot = new NFNotaInfoICMSTotal();
        icmsTot.setBaseCalculoICMS(new BigDecimal(data.getOrDefault("vBC", "0").toString()));
        icmsTot.setValorTotalICMS(new BigDecimal(data.getOrDefault("vICMS", "0").toString()));
        icmsTot.setBaseCalculoICMSST(BigDecimal.ZERO);
        icmsTot.setValorTotalICMSST(BigDecimal.ZERO);
        icmsTot.setValorTotalProdutos(new BigDecimal(data.getOrDefault("vProd", "0").toString()));
        icmsTot.setValorTotalFrete(new BigDecimal(data.getOrDefault("vFrete", "0").toString()));
        icmsTot.setValorTotalSeguro(BigDecimal.ZERO);
        icmsTot.setValorTotalDesconto(new BigDecimal(data.getOrDefault("vDesc", "0").toString()));
        icmsTot.setValorTotalII(BigDecimal.ZERO);
        icmsTot.setValorTotalIPI(BigDecimal.ZERO);
        icmsTot.setValorPIS(BigDecimal.ZERO);
        icmsTot.setValorCOFINS(BigDecimal.ZERO);
        icmsTot.setOutrasDespesasAcessorias(new BigDecimal(data.getOrDefault("vOutro", "0").toString()));
        icmsTot.setValorTotalNFe(new BigDecimal(data.getOrDefault("vNF", "0").toString()));
        icmsTot.setValorTotalTributos(new BigDecimal(data.getOrDefault("vTotTrib", "0").toString()));
        total.setIcmsTotal(icmsTot);
        return total;
    }

    private NFNotaInfoPagamento buildPagamento(Map<String, Object> data) {
        NFNotaInfoPagamento pag = new NFNotaInfoPagamento();
        NFNotaInfoFormaPagamento forma = new NFNotaInfoFormaPagamento();
        forma.setIndicadorFormaPagamento(NFIndicadorFormaPagamento.PAGAMENTO_VISTA);
        // 🎯 USO CORRETO: valueOfCodigo()
        forma.setMeioPagamento(
            NFMeioPagamento.valueOfCodigo((String) data.getOrDefault("tipo", "99")));
        forma.setValorPagamento(new BigDecimal(data.get("valor").toString()));
        pag.setFormasPagamento(Collections.singletonList(forma));
        return pag;
    }

    private String getUfCodigo(String uf) {
        Map<String, String> map = new HashMap<>();
        map.put("SP", "35"); map.put("RJ", "33"); map.put("MG", "31");
        map.put("RS", "43"); map.put("PR", "41"); map.put("SC", "42");
        map.put("BA", "29"); map.put("GO", "52"); map.put("DF", "53");
        // ... adicione todos os estados
        return map.getOrDefault(uf, "35");
    }
}