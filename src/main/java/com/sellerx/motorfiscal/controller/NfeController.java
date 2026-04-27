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

@RestController
@RequestMapping("/api/nfe")
@CrossOrigin("*")
public class NfeController {

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

            // ====== UF dinâmica ======
            String ufSigla = String.valueOf(company.getOrDefault("uf", "SP"));
            DFUnidadeFederativa ufEnum = DFUnidadeFederativa.valueOfSigla(ufSigla);

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

            // ====== NOTA ======
            NFNota nota = new NFNota();
            NFNotaInfo info = new NFNotaInfo();
            info.setVersao(new BigDecimal("4.00"));

            // ---- Identificação ----
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

            // Finalidade dinâmica (1=Normal, 4=Devolução)
            String finalidade = String.valueOf(invoice.getOrDefault("finalidade", "1"));
            switch (finalidade) {
                case "2": ide.setFinalidade(NFFinalidade.COMPLEMENTAR); break;
                case "3": ide.setFinalidade(NFFinalidade.AJUSTE); break;
                case "4": ide.setFinalidade(NFFinalidade.DEVOLUCAO); break;
                default:  ide.setFinalidade(NFFinalidade.NORMAL);
            }

            ide.setOperacaoConsumidorFinal(NFOperacaoConsumidorFinal.SIM);
            ide.setIndicadorPresencaComprador(
                NFIndicadorPresencaComprador.valueOfCodigo(
                    String.valueOf(invoice.getOrDefault("indicador_presenca", "2"))));
            ide.setProgramaEmissor(NFProcessoEmissor.CONTRIBUINTE);
            ide.setVersaoEmissor("1.1.8");
            info.setIdentificacao(ide);

            // ---- Emitente ----
            NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
            emit.setCnpj(String.valueOf(company.get("cnpj")).replaceAll("[^0-9]", ""));
            emit.setRazaoSocial(String.valueOf(company.get("razao_social")));
            emit.setInscricaoEstadual(String.valueOf(company.get("inscricao_estadual")).replaceAll("[^0-9]", ""));
            emit.setRegimeTributario(NFRegimeTributario.SIMPLES_NACIONAL);

            // Endereço Emitente
            Map<String, Object> endEmit = (Map<String, Object>) company.getOrDefault("endereco", new HashMap<>());
            NFEndereco endE = new NFEndereco();
            endE.setUf(DFUnidadeFederativa.valueOfSigla(String.valueOf(endEmit.getOrDefault("uf", ufSigla))));
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

            // ---- Destinatário ----
            NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
            String doc = String.valueOf(customer.getOrDefault("documento", "")).replaceAll("[^0-9]", "");
            if (doc.length() > 11) dest.setCnpj(doc); else dest.setCpf(doc);
            dest.setRazaoSocial(String.valueOf(customer.get("nome")));
            dest.setIndicadorIEDestinatario(NFIndicadorIEDestinatario.NAO_CONTRIBUINTE);

            // Endereço Destinatário
            Map<String, Object> endDest = (Map<String, Object>) customer.getOrDefault("endereco", new HashMap<>());
            NFEndereco endD = new NFEndereco();
            endD.setUf(DFUnidadeFederativa.valueOfSigla(String.valueOf(endDest.getOrDefault("uf", "SP"))));
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
                p.setProdutoCompoeValorNota(true);
                item.setProduto(p);

                // ============ IMPOSTOS — INSTANCIAÇÃO CORRETA ============
                NFNotaInfoItemImposto imp = new NFNotaInfoItemImposto();
                imp.setValorTotalTributos(new BigDecimal("0.00"));

                // ICMS Simples Nacional CSOSN 102
                NFNotaInfoItemImpostoICMSSN102 sn102 = new NFNotaInfoItemImpostoICMSSN102();
                sn102.setOrigem(NFOrigem.NACIONAL);   // ✅ ENUM, não string "0"
                sn102.setCodigoSituacaoOperacaoSimplesNacional(
                    NFNotaInfoItemImpostoICMSSNCodigoSituacaoOperacao.SEM_PERMISSAO_DE_CREDITO);

                NFNotaInfoItemImpostoICMS icms = new NFNotaInfoItemImpostoICMS();
                icms.setIcmssn102(sn102);
                imp.setIcms(icms);

                // PIS — CST 49 (outras operações)
                NFNotaInfoItemImpostoPISOutrasOperacoes pisOutr = new NFNotaInfoItemImpostoPISOutrasOperacoes();
                pisOutr.setSituacaoTributaria(NFNotaInfoItemImpostoPISSituacaoTributaria.OUTRAS_OPERACOES);
                pisOutr.setValorBaseCalculo(new BigDecimal("0.00"));
                pisOutr.setPercentualAliquota(new BigDecimal("0.00"));
                pisOutr.setValor(new BigDecimal("0.00"));
                NFNotaInfoItemImpostoPIS pis = new NFNotaInfoItemImpostoPIS();
                pis.setPisOutrasOperacoes(pisOutr);
                imp.setPis(pis);

                // COFINS — CST 49 (outras operações)
                NFNotaInfoItemImpostoCOFINSOutrasOperacoes cofOutr = new NFNotaInfoItemImpostoCOFINSOutrasOperacoes();
                cofOutr.setSituacaoTributaria(NFNotaInfoItemImpostoCOFINSSituacaoTributaria.OUTRAS_OPERACOES);
                cofOutr.setValorBaseCalculo(new BigDecimal("0.00"));
                cofOutr.setPercentualAliquota(new BigDecimal("0.00"));
                cofOutr.setValor(new BigDecimal("0.00"));
                NFNotaInfoItemImpostoCOFINS cofins = new NFNotaInfoItemImpostoCOFINS();
                cofins.setCofinsOutrasOperacoes(cofOutr);
                imp.setCofins(cofins);

                item.setImposto(imp);
                lista.add(item);
            }
            info.setItens(lista);

            // ---- Totais ----
            String vt = totalProd.setScale(2, RoundingMode.HALF_UP).toPlainString();
            NFNotaInfoTotal total = new NFNotaInfoTotal();
            NFNotaInfoICMSTotal icmsTot = new NFNotaInfoICMSTotal();
            BigDecimal zero = new BigDecimal("0.00");
            icmsTot.setBaseCalculoICMS(zero);
            icmsTot.setValorTotalICMS(zero);
            icmsTot.setValorICMSDesonerado(zero);
            icmsTot.setValorTotalFCP(zero);
            icmsTot.setBaseCalculoICMSST(zero);
            icmsTot.setValorTotalICMSST(zero);
            icmsTot.setValorTotalFCPST(zero);
            icmsTot.setValorTotalFCPSTRetido(zero);
            icmsTot.setValorTotalProdutosEServicos(totalProd);
            icmsTot.setValorTotalFrete(zero);
            icmsTot.setValorTotalSeguro(zero);
            icmsTot.setValorTotalDesconto(zero);
            icmsTot.setValorTotalII(zero);
            icmsTot.setValorTotalIPI(zero);
            icmsTot.setValorTotalIPIDevolvido(zero);
            icmsTot.setValorPIS(zero);
            icmsTot.setValorCOFINS(zero);
            icmsTot.setOutrasDespesasAcessorias(zero);
            icmsTot.setValorTotalNFe(totalProd);
            total.setIcmsTotal(icmsTot);
            info.setTotal(total);

            // ---- Pagamento ----
            NFNotaInfoPagamento pag = new NFNotaInfoPagamento();
            NFNotaInfoPagamentoDetalhe detPag = new NFNotaInfoPagamentoDetalhe();
            detPag.setMeioPagamento(NFNotaInfoPagamentoMeioPagamento.DINHEIRO);
            detPag.setValorPagamento(totalProd);
            pag.setPagamentoDetalhes(Collections.singletonList(detPag));
            info.setPagamento(pag);

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
        return ResponseEntity.ok(Map.of("status", "online", "version", "1.1.8"));
    }
}