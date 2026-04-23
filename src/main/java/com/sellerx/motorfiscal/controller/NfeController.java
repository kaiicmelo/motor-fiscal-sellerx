package com.sellerx.motorfiscal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fincatto.documentofiscal.nfe.WSFacade;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.nfe.classes.nota.NFNota;
import com.fincatto.documentofiscal.nfe.classes.lote.envio.NFLoteEnvioRetorno;
import com.fincatto.documentofiscal.nfe.classes.evento.NFEnviaEventoRetorno;
import com.fincatto.documentofiscal.nfe.classes.statusservico.consulta.NFStatusServicoConsultaRetorno;
import com.fincatto.documentofiscal.nfe.classes.cadastro.NFRetornoConsultaCadastro;
import com.fincatto.documentofiscal.nfe.classes.nota.consulta.NFNotaConsultaRetorno;
import com.fincatto.documentofiscal.DFAmbiente;
import com.fincatto.documentofiscal.DFUnidadeFederativa;
import com.fincatto.documentofiscal.nfe.classes.nota.*;
import com.fincatto.documentofiscal.nfe.classes.DFModelo;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyStore;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.util.Base64;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JRXmlDataSource;

@RestController
@RequestMapping("/api/nfe")
public class NfeController {

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processAction(@RequestBody Map<String, Object> payload) {
        try {
            String action = (String) payload.get("action");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> company = (Map<String, Object>) payload.get("company");
            
            if (action == null || company == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Atributos 'action' ou 'company' ausentes no payload."));
            }

            String certUri = (String) company.get("certificate_file_uri");
            String certPass = (String) company.get("certificate_password");
            String ufString = (String) company.get("uf");
            String ambienteStr = (String) company.getOrDefault("ambiente", "HOMOLOGACAO");

            if (certUri == null || certPass == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Credenciais do certificado ausentes."));
            }

            byte[] pfxBytes = downloadCertificado(certUri);
            
            NFeConfig config = new NFeConfig() {
                @Override
                public DFUnidadeFederativa getCUF() {
                    return ufString != null ? DFUnidadeFederativa.valueOfCodigo(ufString) : DFUnidadeFederativa.SP;
                }
                @Override
                public DFAmbiente getAmbiente() {
                    return "PRODUCAO".equalsIgnoreCase(ambienteStr) ? DFAmbiente.PRODUCAO : DFAmbiente.HOMOLOGACAO;
                }
                @Override
                public KeyStore getCertificadoKeyStore() throws Exception {
                    KeyStore ks = KeyStore.getInstance("PKCS12");
                    ks.load(new ByteArrayInputStream(pfxBytes), getCertificadoSenha().toCharArray());
                    return ks;
                }
                @Override
                public String getCertificadoSenha() {
                    return certPass;
                }
                @Override
                public KeyStore getCadeiaCertificadosKeyStore() throws Exception {
                    return KeyStore.getInstance("JKS"); 
                }
                @Override
                public String getCadeiaCertificadosSenha() {
                    return "changeit";
                }
            };

            Map<String, Object> result = new HashMap<>();
            WSFacade wsFacade = new WSFacade(config);
            
            switch (action) {
                case "emitir":
                    result = handleEmitir(payload, wsFacade);
                    break;
                case "cancelar":
                    result = handleCancelar(payload, wsFacade);
                    break;
                case "carta_correcao":
                    result = handleCartaCorrecao(payload, wsFacade);
                    break;
                case "consultar_status":
                    result = handleConsultarStatus(payload, wsFacade, config.getCUF());
                    break;
                case "consultar_cadastro":
                    result = handleConsultarCadastro(payload, wsFacade, config.getCUF());
                    break;
                case "baixar_xml":
                    result = handleBaixarXml(payload, wsFacade);
                    break;
                case "baixar_pdf":
                    result = handleBaixarPdf(payload, wsFacade);
                    break;
                case "previa":
                    result = handlePrevia(payload, wsFacade);
                    break;
                default:
                    return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Ação não suportada pelo Motor: " + action));
            }
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", "Erro interno no Motor Fiscal: " + e.getMessage()));
        }
    }

    private byte[] downloadCertificado(String uriString) throws Exception {
        URL url = new URL(uriString);
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleEmitir(Map<String, Object> payload, WSFacade wsFacade) throws Exception {
        Map<String, Object> invoice = (Map<String, Object>) payload.get("invoice");
        Map<String, Object> customer = (Map<String, Object>) payload.get("customer");
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

        NFNota nota = new NFNota();
        NFNotaInfo info = new NFNotaInfo();
        nota.setInfo(info);

        if (invoice != null) {
            NFNotaInfoIdentificacao identificador = new NFNotaInfoIdentificacao();
            if (invoice.get("series") != null) identificador.setSerie(invoice.get("series").toString());
            if (invoice.get("numero") != null) identificador.setNumeroNota(invoice.get("numero").toString());
            if (invoice.get("modelo") != null) identificador.setModelo(DFModelo.valueOfCodigo(invoice.get("modelo").toString()));
            if (invoice.get("natureza_operacao") != null) identificador.setNaturezaOperacao(invoice.get("natureza_operacao").toString());
            
            identificador.setDataHoraEmissao(ZonedDateTime.now());
            identificador.setDataHoraSaidaOuEntrada(ZonedDateTime.now());
            
            if (invoice.get("tipo_operacao") != null) {
                identificador.setTipoOperacao(DFTipoOperacao.valueOfCodigo(invoice.get("tipo_operacao").toString()));
            } else {
                identificador.setTipoOperacao(DFTipoOperacao.SAIDA);
            }
            
            if (invoice.get("finalidade") != null) {
                identificador.setFinalidade(DFFinalidade.valueOfCodigo(invoice.get("finalidade").toString()));
            } else {
                identificador.setFinalidade(DFFinalidade.NORMAL);
            }
            
            info.setIdentificacao(identificador);
        }

        if (customer != null) {
            NFNotaInfoDestinatario destinatario = new NFNotaInfoDestinatario();
            String cpfCnpj = (String) customer.get("cpf_cnpj");
            if (cpfCnpj != null) {
                cpfCnpj = cpfCnpj.replaceAll("[^0-9]", "");
                if (cpfCnpj.length() == 14) {
                    destinatario.setCnpj(cpfCnpj);
                } else if (cpfCnpj.length() == 11) {
                    destinatario.setCpf(cpfCnpj);
                }
            }
            if (customer.get("nome") != null) destinatario.setRazaoSocial(customer.get("nome").toString());
            if (customer.get("inscricao_estadual") != null) destinatario.setInscricaoEstadual(customer.get("inscricao_estadual").toString().replaceAll("[^0-9]", ""));
            if (customer.get("email") != null) destinatario.setEmail(customer.get("email").toString());
            
            if (customer.get("indicador_ie") != null) {
                 destinatario.setIndicadorIEDestinatario(NFNotaInfoItemIndicadorIEDestinatario.valueOfCodigo(customer.get("indicador_ie").toString()));
            } else {
                 destinatario.setIndicadorIEDestinatario(NFNotaInfoItemIndicadorIEDestinatario.NAO_CONTRIBUINTE);
            }

            if (customer.get("endereco") != null) {
                Map<String, Object> endData = (Map<String, Object>) customer.get("endereco");
                NFNotaInfoEndereco endereco = new NFNotaInfoEndereco();
                if (endData.get("logradouro") != null) endereco.setLogradouro(endData.get("logradouro").toString());
                if (endData.get("numero") != null) endereco.setNumero(endData.get("numero").toString());
                if (endData.get("complemento") != null) endereco.setComplemento(endData.get("complemento").toString());
                if (endData.get("bairro") != null) endereco.setBairro(endData.get("bairro").toString());
                if (endData.get("codigo_municipio") != null) endereco.setCodigoMunicipio(endData.get("codigo_municipio").toString());
                if (endData.get("nome_municipio") != null) endereco.setDescricaoMunicipio(endData.get("nome_municipio").toString());
                if (endData.get("uf") != null) endereco.setUf(DFUnidadeFederativa.valueOfCodigo(endData.get("uf").toString()));
                if (endData.get("cep") != null) endereco.setCep(endData.get("cep").toString().replaceAll("[^0-9]", ""));
                if (endData.get("telefone") != null) endereco.setTelefone(endData.get("telefone").toString().replaceAll("[^0-9]", ""));
                
                endereco.setCodigoPais("1058");
                endereco.setDescricaoPais("Brasil");
                
                destinatario.setEndereco(endereco);
            }
            
            info.setDestinatario(destinatario);
        }

        if (items != null) {
            List<NFNotaInfoItem> nfItens = new ArrayList<>();
            int seq = 1;
            for (Map<String, Object> itemMap : items) {
                NFNotaInfoItem nfItem = new NFNotaInfoItem();
                nfItem.setNumeroOrdem(String.valueOf(seq++));

                NFNotaInfoItemProduto produto = new NFNotaInfoItemProduto();
                if (itemMap.get("codigo") != null) produto.setCodigo(itemMap.get("codigo").toString());
                if (itemMap.get("descricao") != null) produto.setDescricao(itemMap.get("descricao").toString());
                if (itemMap.get("ncm") != null) produto.setNcm(itemMap.get("ncm").toString().replaceAll("[^0-9]", ""));
                if (itemMap.get("cest") != null) produto.setCest(itemMap.get("cest").toString().replaceAll("[^0-9]", ""));
                if (itemMap.get("cfop") != null) produto.setCfop(itemMap.get("cfop").toString());
                
                if (itemMap.get("unidade") != null) {
                    produto.setUnidadeComercial(itemMap.get("unidade").toString());
                    produto.setUnidadeTributavel(itemMap.get("unidade").toString());
                }
                
                if (itemMap.get("quantidade") != null) {
                    BigDecimal qtd = new BigDecimal(itemMap.get("quantidade").toString());
                    produto.setQuantidadeComercial(qtd);
                    produto.setQuantidadeTributavel(qtd);
                }
                
                if (itemMap.get("valor_unitario") != null) {
                    BigDecimal vlr = new BigDecimal(itemMap.get("valor_unitario").toString());
                    produto.setValorUnitario(vlr);
                    produto.setValorUnitarioTributavel(vlr);
                }
                
                if (itemMap.get("valor_total") != null) {
                    produto.setValorTotalBruto(new BigDecimal(itemMap.get("valor_total").toString()));
                } else if (itemMap.get("quantidade") != null && itemMap.get("valor_unitario") != null) {
                    BigDecimal qtd = new BigDecimal(itemMap.get("quantidade").toString());
                    BigDecimal vlr = new BigDecimal(itemMap.get("valor_unitario").toString());
                    produto.setValorTotalBruto(qtd.multiply(vlr));
                }
                
                nfItem.setProduto(produto);

                NFNotaInfoItemImposto imposto = new NFNotaInfoItemImposto();
                
                if (itemMap.containsKey("icms")) {
                    Map<String, Object> icmsData = (Map<String, Object>) itemMap.get("icms");
                    String cst = (String) icmsData.getOrDefault("cst", "");
                    String csosn = (String) icmsData.getOrDefault("csosn", "");
                    String origemStr = (String) icmsData.getOrDefault("origem", "0");
                    com.fincatto.documentofiscal.nfe.classes.DFOrigemMercadoria origem = com.fincatto.documentofiscal.nfe.classes.DFOrigemMercadoria.valueOfCodigo(origemStr);
                    
                    NFNotaInfoItemImpostoICMS icms = new NFNotaInfoItemImpostoICMS();
                    
                    if (!csosn.isEmpty()) {
                        if (csosn.equals("102") || csosn.equals("103") || csosn.equals("300") || csosn.equals("400")) {
                            NFNotaInfoItemImpostoICMSSN102 icmsSn = new NFNotaInfoItemImpostoICMSSN102();
                            icmsSn.setOrigem(origem);
                            icmsSn.setCsosn(DFNotaInfoItemImpostoICMSSNCSOSN.valueOfCodigo(csosn));
                            icms.setIcmsSn102(icmsSn);
                        } else if (csosn.equals("101")) {
                            NFNotaInfoItemImpostoICMSSN101 icmsSn = new NFNotaInfoItemImpostoICMSSN101();
                            icmsSn.setOrigem(origem);
                            icmsSn.setCsosn(DFNotaInfoItemImpostoICMSSNCSOSN.valueOfCodigo(csosn));
                            if (icmsData.get("pCredSN") != null) icmsSn.setPercentualAliquotaAplicavelCalculoCreditoSN(new BigDecimal(icmsData.get("pCredSN").toString()));
                            if (icmsData.get("vCredICMSSN") != null) icmsSn.setValorCreditoICMSSN(new BigDecimal(icmsData.get("vCredICMSSN").toString()));
                            icms.setIcmsSn101(icmsSn);
                        } else if (csosn.equals("500")) {
                            NFNotaInfoItemImpostoICMSSN500 icmsSn = new NFNotaInfoItemImpostoICMSSN500();
                            icmsSn.setOrigem(origem);
                            icmsSn.setCsosn(DFNotaInfoItemImpostoICMSSNCSOSN.valueOfCodigo(csosn));
                            icms.setIcmsSn500(icmsSn);
                        }
                    } else if (!cst.isEmpty()) {
                        if (cst.equals("00")) {
                            NFNotaInfoItemImpostoICMS00 icms00 = new NFNotaInfoItemImpostoICMS00();
                            icms00.setOrigem(origem);
                            icms00.setCst(DFNotaInfoItemImpostoICMSCST.valueOfCodigo(cst));
                            icms00.setModalidadeBCICMS(DFNotaInfoItemModalidadeBCICMS.VALOR_OPERACAO);
                            if (icmsData.get("base_calculo") != null) icms00.setValorBaseCalculo(new BigDecimal(icmsData.get("base_calculo").toString()));
                            if (icmsData.get("aliquota") != null) icms00.setPercentualAliquota(new BigDecimal(icmsData.get("aliquota").toString()));
                            if (icmsData.get("valor") != null) icms00.setValorTributo(new BigDecimal(icmsData.get("valor").toString()));
                            icms.setIcms00(icms00);
                        } else if (cst.equals("40") || cst.equals("41") || cst.equals("50")) {
                            NFNotaInfoItemImpostoICMS40 icms40 = new NFNotaInfoItemImpostoICMS40();
                            icms40.setOrigem(origem);
                            icms40.setCst(DFNotaInfoItemImpostoICMSCST.valueOfCodigo(cst));
                            icms.setIcms40(icms40);
                        } else if (cst.equals("60")) {
                            NFNotaInfoItemImpostoICMS60 icms60 = new NFNotaInfoItemImpostoICMS60();
                            icms60.setOrigem(origem);
                            icms60.setCst(DFNotaInfoItemImpostoICMSCST.valueOfCodigo(cst));
                            icms.setIcms60(icms60);
                        }
                    }
                    imposto.setIcms(icms);
                }

                if (itemMap.containsKey("pis")) {
                    Map<String, Object> pisData = (Map<String, Object>) itemMap.get("pis");
                    String cst = (String) pisData.getOrDefault("cst", "01");
                    NFNotaInfoItemImpostoPIS pis = new NFNotaInfoItemImpostoPIS();
                    
                    if (cst.equals("01") || cst.equals("02")) {
                        NFNotaInfoItemImpostoPISAliquota pisAliq = new NFNotaInfoItemImpostoPISAliquota();
                        pisAliq.setCst(com.fincatto.documentofiscal.nfe.classes.nota.DFNotaInfoItemImpostoPISCST.valueOfCodigo(cst));
                        if (pisData.get("base_calculo") != null) pisAliq.setValorBaseCalculo(new BigDecimal(pisData.get("base_calculo").toString()));
                        if (pisData.get("aliquota") != null) pisAliq.setPercentualAliquota(new BigDecimal(pisData.get("aliquota").toString()));
                        if (pisData.get("valor") != null) pisAliq.setValorTributo(new BigDecimal(pisData.get("valor").toString()));
                        pis.setAliquota(pisAliq);
                    } else if (cst.equals("04") || cst.equals("06") || cst.equals("07") || cst.equals("08") || cst.equals("09")) {
                        NFNotaInfoItemImpostoPISNaoTributado pisNt = new NFNotaInfoItemImpostoPISNaoTributado();
                        pisNt.setCst(com.fincatto.documentofiscal.nfe.classes.nota.DFNotaInfoItemImpostoPISCST.valueOfCodigo(cst));
                        pis.setNaoTributado(pisNt);
                    } else if (cst.equals("99")) {
                        NFNotaInfoItemImpostoPISOutrasOperacoes pisOutr = new NFNotaInfoItemImpostoPISOutrasOperacoes();
                        pisOutr.setCst(com.fincatto.documentofiscal.nfe.classes.nota.DFNotaInfoItemImpostoPISCST.valueOfCodigo(cst));
                        if (pisData.get("base_calculo") != null) pisOutr.setValorBaseCalculo(new BigDecimal(pisData.get("base_calculo").toString()));
                        if (pisData.get("aliquota") != null) pisOutr.setPercentualAliquota(new BigDecimal(pisData.get("aliquota").toString()));
                        if (pisData.get("valor") != null) pisOutr.setValorTributo(new BigDecimal(pisData.get("valor").toString()));
                        pis.setOutrasOperacoes(pisOutr);
                    }
                    imposto.setPis(pis);
                }

                if (itemMap.containsKey("cofins")) {
                    Map<String, Object> cofinsData = (Map<String, Object>) itemMap.get("cofins");
                    String cst = (String) cofinsData.getOrDefault("cst", "01");
                    NFNotaInfoItemImpostoCOFINS cofins = new NFNotaInfoItemImpostoCOFINS();
                    
                    if (cst.equals("01") || cst.equals("02")) {
                        NFNotaInfoItemImpostoCOFINSAliquota cofinsAliq = new NFNotaInfoItemImpostoCOFINSAliquota();
                        cofinsAliq.setCst(com.fincatto.documentofiscal.nfe.classes.nota.DFNotaInfoItemImpostoCOFINSCST.valueOfCodigo(cst));
                        if (cofinsData.get("base_calculo") != null) cofinsAliq.setValorBaseCalculo(new BigDecimal(cofinsData.get("base_calculo").toString()));
                        if (cofinsData.get("aliquota") != null) cofinsAliq.setPercentualAliquota(new BigDecimal(cofinsData.get("aliquota").toString()));
                        if (cofinsData.get("valor") != null) cofinsAliq.setValorTributo(new BigDecimal(cofinsData.get("valor").toString()));
                        cofins.setAliquota(cofinsAliq);
                    } else if (cst.equals("04") || cst.equals("06") || cst.equals("07") || cst.equals("08") || cst.equals("09")) {
                        NFNotaInfoItemImpostoCOFINSNaoTributavel cofinsNt = new NFNotaInfoItemImpostoCOFINSNaoTributavel();
                        cofinsNt.setCst(com.fincatto.documentofiscal.nfe.classes.nota.DFNotaInfoItemImpostoCOFINSCST.valueOfCodigo(cst));
                        cofins.setNaoTributado(cofinsNt);
                    } else if (cst.equals("99")) {
                        NFNotaInfoItemImpostoCOFINSOutrasOperacoes cofinsOutr = new NFNotaInfoItemImpostoCOFINSOutrasOperacoes();
                        cofinsOutr.setCst(com.fincatto.documentofiscal.nfe.classes.nota.DFNotaInfoItemImpostoCOFINSCST.valueOfCodigo(cst));
                        if (cofinsData.get("base_calculo") != null) cofinsOutr.setValorBaseCalculo(new BigDecimal(cofinsData.get("base_calculo").toString()));
                        if (cofinsData.get("aliquota") != null) cofinsOutr.setPercentualAliquota(new BigDecimal(cofinsData.get("aliquota").toString()));
                        if (cofinsData.get("valor") != null) cofinsOutr.setValorTributo(new BigDecimal(cofinsData.get("valor").toString()));
                        cofins.setOutrasOperacoes(cofinsOutr);
                    }
                    imposto.setCofins(cofins);
                }

                nfItem.setImposto(imposto);
                nfItens.add(nfItem);
            }
            info.setItens(nfItens);
        }

        NFLoteEnvioRetorno retorno = wsFacade.enviaLote(nota);
        
        String cStat = retorno.getStatus();
        String xMotivo = retorno.getMotivo();
        String nRecibo = "";
        
        if (retorno.getInfoRecebimento() != null) {
            nRecibo = retorno.getInfoRecebimento().getRecibo();
        }

        return Map.of(
            "status", (cStat != null && cStat.equals("103")) ? "sucesso" : "erro",
            "cStat", cStat != null ? cStat : "",
            "mensagem", xMotivo != null ? xMotivo : "Erro desconhecido",
            "recibo", nRecibo,
            "xml_retorno", retorno.toString()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleCancelar(Map<String, Object> payload, WSFacade wsFacade) throws Exception {
        Map<String, Object> invoice = (Map<String, Object>) payload.get("invoice");
        String chave = (String) invoice.get("chave");
        String protocolo = (String) invoice.get("protocolo");
        String justificativa = (String) payload.get("justificativa");
        
        NFEnviaEventoRetorno retorno = wsFacade.cancelaNota(chave, protocolo, justificativa);
        
        if (retorno.getEventos() != null && !retorno.getEventos().isEmpty()) {
            var evtRetorno = retorno.getEventos().get(0).getEventoRetorno();
            String cStat = evtRetorno.getStatus();
            return Map.of(
                "status", (cStat != null && cStat.equals("135")) ? "sucesso" : "erro",
                "cStat", cStat != null ? cStat : "",
                "mensagem", evtRetorno.getMotivo() != null ? evtRetorno.getMotivo() : "",
                "protocolo_cancelamento", evtRetorno.getNumeroProtocolo() != null ? evtRetorno.getNumeroProtocolo() : "",
                "data_registro", evtRetorno.getDataHoraRegistro() != null ? evtRetorno.getDataHoraRegistro().toString() : ""
            );
        }
        return Map.of("status", "erro", "mensagem", "Falha ao processar evento de cancelamento.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleCartaCorrecao(Map<String, Object> payload, WSFacade wsFacade) throws Exception {
        Map<String, Object> invoice = (Map<String, Object>) payload.get("invoice");
        String chave = (String) invoice.get("chave");
        String correcao = (String) payload.get("correcao");
        int sequencia = payload.containsKey("sequencia") ? (int) payload.get("sequencia") : 1;
        
        NFEnviaEventoRetorno retorno = wsFacade.corrigeNota(chave, correcao, sequencia);
        
        if (retorno.getEventos() != null && !retorno.getEventos().isEmpty()) {
            var evtRetorno = retorno.getEventos().get(0).getEventoRetorno();
            String cStat = evtRetorno.getStatus();
            return Map.of(
                "status", (cStat != null && cStat.equals("135")) ? "sucesso" : "erro",
                "cStat", cStat != null ? cStat : "",
                "mensagem", evtRetorno.getMotivo() != null ? evtRetorno.getMotivo() : "",
                "protocolo_correcao", evtRetorno.getNumeroProtocolo() != null ? evtRetorno.getNumeroProtocolo() : ""
            );
        }
        return Map.of("status", "erro", "mensagem", "Falha ao processar evento de correção.");
    }

    private Map<String, Object> handleConsultarStatus(Map<String, Object> payload, WSFacade wsFacade, DFUnidadeFederativa uf) throws Exception {
        NFStatusServicoConsultaRetorno retorno = wsFacade.consultaStatus(uf);
        return Map.of(
            "status", (retorno.getStatus() != null && retorno.getStatus().equals("107")) ? "sucesso" : "erro",
            "cStat", retorno.getStatus() != null ? retorno.getStatus() : "",
            "mensagem", retorno.getMotivo() != null ? retorno.getMotivo() : "Sem comunicação",
            "tempo_medio", retorno.getTempoMedio() != null ? retorno.getTempoMedio() : 0
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleConsultarCadastro(Map<String, Object> payload, WSFacade wsFacade, DFUnidadeFederativa uf) throws Exception {
        Map<String, Object> customer = (Map<String, Object>) payload.get("customer");
        if (customer == null || !customer.containsKey("cnpj")) {
            return Map.of("status", "erro", "mensagem", "CNPJ do cliente não informado para consulta de cadastro.");
        }
        String cnpj = (String) customer.get("cnpj");
        
        NFRetornoConsultaCadastro retorno = wsFacade.consultaCadastro(cnpj, uf);
        
        return Map.of(
            "status", retorno.getSituacao() != null ? retorno.getSituacao() : "concluido",
            "mensagem", retorno.getMotivo() != null ? retorno.getMotivo() : "Consulta processada",
            "xml_retorno", retorno.toString()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBaixarXml(Map<String, Object> payload, WSFacade wsFacade) throws Exception {
        Map<String, Object> invoice = (Map<String, Object>) payload.get("invoice");
        if (invoice == null || !invoice.containsKey("chave")) {
            return Map.of("status", "erro", "mensagem", "Chave da nota não informada para baixar XML.");
        }
        String chave = (String) invoice.get("chave");
        
        NFNotaConsultaRetorno retorno = wsFacade.consultaNota(chave);
        
        return Map.of(
            "status", (retorno.getStatus() != null && retorno.getStatus().equals("100")) ? "sucesso" : "erro",
            "cStat", retorno.getStatus() != null ? retorno.getStatus() : "",
            "mensagem", retorno.getMotivo() != null ? retorno.getMotivo() : "Consulta XML finalizada",
            "xml_retorno", retorno.toString() 
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBaixarPdf(Map<String, Object> payload, WSFacade wsFacade) throws Exception {
        Map<String, Object> xmlResponse = handleBaixarXml(payload, wsFacade);
        
        if ("erro".equals(xmlResponse.get("status"))) {
            return xmlResponse;
        }

        String xmlBase = (String) xmlResponse.get("xml_retorno");
        String base64Pdf = "";

        try {
            InputStream templateStream = getClass().getResourceAsStream("/danfe.jrxml");
            if (templateStream == null) {
                return Map.of("status", "erro", "mensagem", "Template danfe.jrxml não encontrado nos resources da aplicação.");
            }
            JasperReport report = JasperCompileManager.compileReport(templateStream);
            JRXmlDataSource xmlDataSource = new JRXmlDataSource(new ByteArrayInputStream(xmlBase.getBytes("UTF-8")), "/nfeProc/NFe");
            Map<String, Object> params = new HashMap<>(); 
            JasperPrint print = JasperFillManager.fillReport(report, params, xmlDataSource);
            byte[] pdfBytes = JasperExportManager.exportReportToPdf(print);
            base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);
        } catch (Exception e) {
            return Map.of("status", "erro", "mensagem", "Erro ao compilar/gerar PDF com JasperReports: " + e.getMessage());
        }

        return Map.of(
            "status", "sucesso",
            "mensagem", "PDF do DANFE gerado com sucesso.",
            "pdf_base64", base64Pdf,
            "xml_base", xmlBase
        );
    }

    private Map<String, Object> handlePrevia(Map<String, Object> payload, WSFacade wsFacade) throws Exception {
        NFNota nota = new NFNota();
        String xmlPrevia = nota.toString(); 
        
        return Map.of(
            "status", "sucesso",
            "mensagem", "Prévia gerada com sucesso, sem assinatura e sem envio para SEFAZ.",
            "xml_previa", xmlPrevia != null ? xmlPrevia : ""
        );
    }
}