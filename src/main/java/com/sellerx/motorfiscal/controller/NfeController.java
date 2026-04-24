package com.sellerx.motorfiscal.controller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fincatto.documentofiscal.nfe400.webservices.WSFacade;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.*;
import com.fincatto.documentofiscal.nfe400.classes.nota.*;
import com.fincatto.documentofiscal.nfe400.classes.*;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.*;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.util.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

@RestController
@RequestMapping("/api/nfe")
@CrossOrigin("*")
public class NfeController {
    @PostMapping("/process")
    public ResponseEntity<?> process(@RequestBody Map<String, Object> payload) {
        try {
            Map<String, Object> company = (Map<String, Object>) payload.get("company");
            String certUri = (String) company.get("certificate_file_uri");
            String certPass = (String) company.get("certificate_password");
            
            URL url = new URL(certUri);
            byte[] pfx;
            try (InputStream in = url.openStream()) { pfx = in.readAllBytes(); }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pfx), certPass.toCharArray());

            NFeConfig config = new NFeConfig() {
                @Override public DFUnidadeFederativa getCUF() { return DFUnidadeFederativa.valueOfCodigo((String)company.getOrDefault("uf", "35")); }
                @Override public DFAmbiente getAmbiente() { return "PRODUCAO".equals(company.get("ambiente")) ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO; }
                @Override public KeyStore getCertificadoKeyStore() { return ks; }
                @Override public String getCertificadoSenha() { return certPass; }
                @Override public KeyStore getCadeiaCertificadosKeyStore() { return ks; }
                @Override public String getCadeiaCertificadosSenha() { return certPass; }
            };

            NFNota nota = new NFNota();
            NFNotaInfo info = new NFNotaInfo();
            info.setIdentificador("00000000000000000000000000000000000000000000");

            NFNotaInfoIdentificacao ide = new NFNotaInfoIdentificacao();
            ide.setUf(config.getCUF());
            ide.setCodigoRandomico(String.format("%08d", new Random().nextInt(99999999)));
            ide.setNaturezaOperacao("VENDA");
            ide.setModelo(DFModelo.NFE);
            ide.setSerie("1");
            ide.setNumeroNota("1");
            ide.setDataHoraEmissao(ZonedDateTime.now());
            
            // FIX DEFINITIVO PARA COMPILACAO (EXPLICIT NAMES)
            ide.setTipoDocumento(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoTipoDocumento.valueOfCodigo("1"));
            ide.setIdentificadorLocalDestinoOperacao(com.fincatto.documentofiscal.nfe400.classes.NFIdentificadorLocalDestinoOperacao.valueOfCodigo("1"));
            ide.setCodigoMunicipio("3516200");
            ide.setTipoImpressao(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoTipoImpressao.valueOfCodigo("1"));
            ide.setTipoEmissao(com.fincatto.documentofiscal.nfe.NFTipoEmissao.valueOfCodigo("1"));
            ide.setAmbiente(config.getAmbiente());
            ide.setFinalidade(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoFinalidade.valueOfCodigo("1"));
            ide.setOperacaoConsumidorFinal(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoOperacaoConsumidorFinal.valueOfCodigo("1"));
            ide.setIndicadorPresencaComprador(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoIndPresencaComprador.valueOfCodigo("1"));
            info.setIdentificacao(ide);

            NFNotaInfoEmitente emit = new NFNotaInfoEmitente();
            emit.setCnpj((String)company.get("cnpj"));
            emit.setRazaoSocial((String)company.get("razao_social"));
            emit.setInscricaoEstadual("ISENTO");
            emit.setCrt(com.fincatto.documentofiscal.nfe.classes.CRT.valueOfCodigo("1"));
            NFEndereco endE = new NFEndereco();
            endE.setLogradouro("RUA"); endE.setNumero("1"); endE.setBairro("B"); endE.setCodigoMunicipio("3516200");
            endE.setDescricaoMunicipio("FRANCA"); endE.setUf(config.getCUF()); endE.setCep("14400000");
            emit.setEndereco(endE);
            info.setEmitente(emit);

            NFNotaInfoDestinatario dest = new NFNotaInfoDestinatario();
            dest.setCpf("00000000000"); dest.setRazaoSocial("CONSUMIDOR");
            dest.setIndicadorIEDestinatario(com.fincatto.documentofiscal.nfe400.classes.NFIndicadorIEDestinatario.valueOfCodigo("9"));
            info.setDestinatario(dest);

            NFNotaInfoItem item = new NFNotaInfoItem();
            NFNotaInfoItemProduto prod = new NFNotaInfoItemProduto();
            prod.setCodigo("1"); prod.setDescricao("PRODUTO"); prod.setNcm("94039000"); prod.setCfop("5102");
            prod.setUnidadeComercial("UN"); prod.setQuantidadeComercial(new BigDecimal("1.00"));
            prod.setValorUnitario(new BigDecimal("10.00")); prod.setValorTotalBruto(new BigDecimal("10.00"));
            prod.setIndicaTotal(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoItemIndicadorTotal.valueOfCodigo("1"));
            item.setProduto(prod);
            
            NFNotaInfoItemImposto imp = new NFNotaInfoItemImposto();
            NFNotaInfoItemImpostoICMS icms = new NFNotaInfoItemImpostoICMS();
            NFNotaInfoItemImpostoICMSSN102 sn = new NFNotaInfoItemImpostoICMSSN102();
            sn.setOrigem(com.fincatto.documentofiscal.nfe400.classes.NFOrigem.valueOfCodigo("0"));
            sn.setSituacaoOperacaoSN(com.fincatto.documentofiscal.nfe400.classes.NFNotaSituacaoOperacionalSimplesNacional.valueOfCodigo("102"));
            icms.setIcmsSn102(sn); imp.setIcms(icms); item.setImposto(imp);
            info.setItens(Collections.singletonList(item));

            NFNotaInfoTotal total = new NFNotaInfoTotal();
            NFNotaInfoICMSTotal icmsT = new NFNotaInfoICMSTotal();
            icmsT.setBaseCalculoICMS(BigDecimal.ZERO); icmsT.setValorTotalICMS(BigDecimal.ZERO);
            icmsT.setValorTotalNFe(new BigDecimal("10.00"));
            total.setIcmsTotal(icmsT); info.setTotal(total);

            NFNotaInfoPagamento pag = new NFNotaInfoPagamento();
            // FIX: PAGAMENTO DETALHE CLASS
            com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoPagamentoDetalhe det = new com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoPagamentoDetalhe();
            det.setFormaPagamento(com.fincatto.documentofiscal.nfe400.classes.nota.NFNotaInfoFormaPagamento.valueOfCodigo("01"));
            det.setValorPagamento(new BigDecimal("10.00"));
            pag.setDetalhamentoPagamentos(Collections.singletonList(det));
            info.setPagamento(pag);

            NFNotaInfoTransporte tr = new NFNotaInfoTransporte();
            tr.setModalidadeFrete(com.fincatto.documentofiscal.nfe400.classes.NFNotaInfoModalidadeFrete.valueOfCodigo("9"));
            info.setTransporte(tr);

            nota.setInfo(info);
            NFLoteEnvio lote = new NFLoteEnvio();
            lote.setNotas(Collections.singletonList(nota)); lote.setIdLote("1"); lote.setVersao("4.00");
            lote.setIndicadorProcessamento(com.fincatto.documentofiscal.nfe400.classes.lote.envio.NFLoteIndicadorProcessamento.valueOfCodigo("1"));

            WSFacade ws = new WSFacade(config);
            NFLoteEnvioRetornoDados res = ws.enviaLote(lote);
            return ResponseEntity.ok(Map.of("status", res.getRetorno().getStatus(), "motivo", res.getRetorno().getMotivo()));
        } catch (Exception e) { return ResponseEntity.status(500).body(e.getMessage()); }
    }
    @GetMapping("/process") public ResponseEntity<?> ping() { return ResponseEntity.ok(Map.of("status", "online")); }
}
// Sync: 2026-04-24T12:22:23.553Z