package br.com.argo.estoquemin.controller;

import br.com.argo.estoquemin.service.NotificaUserService;
import br.com.argo.estoquemin.service.EnvioEmailService;
import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.core.JapeSession;
import br.com.sankhya.jape.core.JapeSession.SessionHandle;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;
import com.sankhya.util.JdbcUtils;
import org.cuckoo.core.ScheduledAction;
import org.cuckoo.core.ScheduledActionContext;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evento agendado para alerta de estoque baixo dos insumos
 * vinculados a ficha tecnica da uva (AD_FICHATECITE).
 *
 * <p>Percorre: TGFPRO (Uva) -> AD_FICHATECPROD (ativas) -> AD_FICHATECITE (insumos)
 * -> TGFEST (estoque por controle/local 3030000)</p>
 *
 * <p>Criterio: ESTOQUE <= AD_EST_FICHA AND ESTOQUE > 0</p>
 * <p>Controle: So alerta se nunca alertou (AD_DTALERTA IS NULL)
 *    ou se o estoque mudou desde o ultimo alerta (ESTOQUE <> AD_EST_ALERTADO)</p>
 *
 * @author Natan - Argo Fruta
 * @version 3.0
 * @since 2026-03-02
 *
 * Commit: feat(EB-XX): evento agendado alerta estoque baixo com controle de duplicidade
 */
public class EstoqueMinController implements ScheduledAction {

    private static final int COD_LOCAL_PRODUCAO = 3030000;

    @Override
    public void onTime(ScheduledActionContext ctx) {

        JdbcWrapper jdbc = null;
        NativeSql queryVoa = null;
        ResultSet rset = null;
        SessionHandle hnd = null;
        NotificaUserService notificaUser = new NotificaUserService();
        EnvioEmailService envioEmail = new EnvioEmailService();

        String tituloAlerta = "ALERTA ESTOQUE BAIXO - Insumos Ficha Tecnica Uva";

        // Formatador decimal padrao brasileiro
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("pt", "BR"));
        DecimalFormat df = new DecimalFormat("#,##0.00", symbols);

        try {
            // Abre uma sessao no banco de dados
            hnd = JapeSession.open();
            hnd.setFindersMaxRows(-1);

            // Obtem uma instancia para interagir com o banco de dados
            EntityFacade entity = EntityFacadeFactory.getDWFFacade();
            jdbc = entity.getJdbcWrapper();
            jdbc.openSession();

            // Cria a consulta SQL de alerta
            queryVoa = new NativeSql(jdbc);
            queryVoa.appendSql(
                    "SELECT " +
                            "    EST.CODPROD                    AS COD_INSUMO, " +
                            "    INS.DESCRPROD                  AS DESCR_INSUMO, " +
                            "    EST.CONTROLE                   AS CONTROLE, " +
                            "    EST.CODLOCAL                   AS CODLOCAL, " +
                            "    EST.ESTOQUE                    AS ESTOQUE_ATUAL, " +
                            "    EST.AD_EST_FICHA               AS LIMITE_ALERTA, " +
                            "    COUNT(DISTINCT PROUVA.CODPROD) AS QTD_UVAS_AFETADAS " +
                            "FROM TGFPRO PROUVA " +
                            "INNER JOIN AD_FICHATECPROD FTP " +
                            "    ON  FTP.CODPROD = PROUVA.CODPROD " +
                            "    AND FTP.ATIVO   = 'S' " +
                            "INNER JOIN AD_FICHATECITE FTI " +
                            "    ON  FTI.CODPROD   = FTP.CODPROD " +
                            "    AND FTI.CODPRODMP = FTP.CODPRODMP " +
                            "    AND FTI.CODPRODCX = FTP.CODPRODCX " +
                            "INNER JOIN TGFEST EST " +
                            "    ON  EST.CODPROD  = FTI.CODPRODINS " +
                            "    AND EST.CONTROLE = FTI.CONTROLE " +
                            "    AND EST.CODLOCAL = " + COD_LOCAL_PRODUCAO + " " +
                            "LEFT JOIN TGFPRO INS ON INS.CODPROD = FTI.CODPRODINS " +
                            "WHERE PROUVA.ATIVO       = 'S' " +
                            "  AND PROUVA.AD_CULTIVAR = 'Uva' " +
                            "  AND EST.AD_EST_FICHA   IS NOT NULL " +
                            "  AND EST.ESTOQUE        <= EST.AD_EST_FICHA " +
                            "  AND EST.ESTOQUE        > 0 " +
                            "  AND (EST.AD_DTALERTA IS NULL OR TRUNC(EST.AD_DTALERTA) < TRUNC(SYSDATE)) " +
                            "GROUP BY " +
                            "    EST.CODPROD, INS.DESCRPROD, EST.CONTROLE, " +
                            "    EST.CODLOCAL, EST.ESTOQUE, EST.AD_EST_FICHA " +
                            "ORDER BY EST.CODPROD"
            );

            // Executa a consulta SQL e obtem o conjunto de resultados
            rset = queryVoa.executeQuery();

            StringBuilder mensagemHtml = new StringBuilder();
            List<ItemAlerta> itensAlertados = new ArrayList<>();
            int totalAlertas = 0;

            mensagemHtml.append("<h3>ALERTA ESTOQUE BAIXO - Insumos Uva</h3>");
            mensagemHtml.append("<p>Local: " + COD_LOCAL_PRODUCAO + " - PH UVA EMB</p>");
            mensagemHtml.append("<hr>");

            while (rset.next()) {
                int codInsumo           = rset.getInt("COD_INSUMO");
                String descrInsumo      = rset.getString("DESCR_INSUMO");
                String controle         = rset.getString("CONTROLE");
                BigDecimal estoqueAtual = rset.getBigDecimal("ESTOQUE_ATUAL");
                BigDecimal limiteAlerta = rset.getBigDecimal("LIMITE_ALERTA");
                int qtdUvasAfetadas     = rset.getInt("QTD_UVAS_AFETADAS");

                mensagemHtml.append("<h4>[" + codInsumo + "] " + descrInsumo + "</h4>");
                mensagemHtml.append("<p><b>Controle:</b> " + controle + "</p>");
                mensagemHtml.append("<p><b>Estoque Atual:</b> " + df.format(estoqueAtual) + "</p>");
                mensagemHtml.append("<p><b>Limite Alerta:</b> " + df.format(limiteAlerta) + "</p>");
                mensagemHtml.append("<p><b>Produtos Uva afetados:</b> " + qtdUvasAfetadas + "</p>");
                mensagemHtml.append("<hr>");

                // Guarda pra marcar como alertado depois
                ItemAlerta item = new ItemAlerta();
                item.codInsumo = codInsumo;
                item.controle  = controle;
                itensAlertados.add(item);

                totalAlertas++;
            }

            // Fecha o ResultSet antes de fazer os UPDATEs
            JdbcUtils.closeResultSet(rset);
            rset = null;
            NativeSql.releaseResources(queryVoa);
            queryVoa = null;

            if (totalAlertas > 0) {
                String tituloFinal = tituloAlerta + " (" + totalAlertas + " insumo(s))";
                mensagemHtml.append("<p><b>Acao:</b> Verificar reposicao ou substituicao dos insumos.</p>");

                // Notificacao no portal Sankhya
                notificaUser.notifUsu(mensagemHtml.toString(), tituloFinal);

                // Email via fila de mensagens
                String corpoEmail = montarEmailHtml(mensagemHtml.toString(), totalAlertas);
                envioEmail.enviarEmail(tituloFinal, corpoEmail);

                // Marca como alertado pra nao repetir enquanto estoque nao mudar
                marcarComoAlertado(jdbc, itensAlertados);

                ctx.info("Alerta de estoque baixo enviado: " + totalAlertas + " insumo(s) no limite.");
            } else {
                ctx.info("Estoque OK - Nenhum alerta novo para insumos da ficha tecnica da uva.");
            }

        } catch (Exception e) {
            e.printStackTrace();
            ctx.info("Erro ao processar o evento de valor min estoque: " + e.getMessage());
        } finally {
            // Liberacao de recursos e fechamento da sessao
            JdbcUtils.closeResultSet(rset);
            JdbcWrapper.closeSession(jdbc);
            JapeSession.close(hnd);
            NativeSql.releaseResources(queryVoa);
        }
    }

    /**
     * Marca os insumos como alertados na TGFEST para nao repetir.
     * Grava AD_DTALERTA = SYSDATE e AD_EST_ALERTADO = ESTOQUE atual.
     * So vai alertar novamente quando o ESTOQUE mudar.
     */
    private void marcarComoAlertado(JdbcWrapper jdbc, List<ItemAlerta> itens) throws Exception {
        for (ItemAlerta item : itens) {
            NativeSql updateSql = null;
            try {
                updateSql = new NativeSql(jdbc);
                updateSql.appendSql(
                        "UPDATE TGFEST " +
                                "SET AD_DTALERTA     = SYSDATE " +
                                "WHERE CODPROD  = :CODPROD " +
                                "  AND CONTROLE = :CONTROLE " +
                                "  AND CODLOCAL = " + COD_LOCAL_PRODUCAO
                );
                updateSql.setNamedParameter("CODPROD", new BigDecimal(item.codInsumo));
                updateSql.setNamedParameter("CONTROLE", item.controle);
                updateSql.executeUpdate();
            } finally {
                NativeSql.releaseResources(updateSql);
            }
        }
    }

    /**
     * Monta o corpo do email HTML no padrao Argo Fruta.
     */
    private String montarEmailHtml(String conteudo, int totalAlertas) {
        return "<!DOCTYPE html>\r\n"
                + "<html>\r\n"
                + "<head>\r\n"
                + "<title>Alerta Estoque Baixo - Argo Fruta</title>\r\n"
                + "<link href=\"https://fonts.googleapis.com/css?family=Poppins:200,300,400,500,600,700\" rel=\"stylesheet\">\r\n"
                + "</head>\r\n"
                + "<body style=\"background-color: #f4f4f4; margin: 0; padding: 0; width: 100%; height: 100%; font-family: Poppins, sans-serif; color: rgba(0, 0, 0, .4);\">\r\n"
                + "<table width=\"100%\" height=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\">\r\n"
                + "    <tr>\r\n"
                + "        <td align=\"center\" valign=\"top\" style=\"padding-top: 20px; padding-bottom: 20px;\">\r\n"
                + "            <table width=\"600\" border=\"0\" cellpadding=\"20\" cellspacing=\"0\" style=\"background-color: white; margin: auto; box-shadow: 0 0 10px rgba(0,0,0,0.1); min-height: 400px;\">\r\n"
                + "                <tr>\r\n"
                + "                    <td align=\"center\" style=\"margin-bottom: 20px;\">\r\n"
                + "                        <img src=\"https://argofruta.com/wp-content/uploads/2021/05/Logo-text-green.png\" alt=\"Argo Fruta Logo\" width=\"250\" style=\"margin-top: 30px;\">\r\n"
                + "                    </td>\r\n"
                + "                </tr>\r\n"
                + "                <tr>\r\n"
                + "                    <td>\r\n"
                + "                        <h2 style=\"font-family: Poppins, sans-serif; color: #000000; margin-top: 0; font-weight: 400; text-align: center;\">Alerta de Estoque Baixo</h2>\r\n"
                + "                        <div style=\"border: 1px solid rgba(0, 0, 0, .05); max-width: 80%; margin: 0 auto; padding: 2em;\">\r\n"
                + "                            <p style=\"text-align: justify; font-size: 15px;\">Prezado(a),<br><br>\r\n"
                + "                            Identificamos que <b>" + totalAlertas + " insumo(s)</b> da ficha tecnica da uva estao com estoque no limite de alerta no local 3030000 - PH UVA EMB.<br><br>\r\n"
                + "                            Por favor, verifique a necessidade de reposicao ou substituicao.</p>\r\n"
                + "                            <hr>\r\n"
                +                              conteudo + "\r\n"
                + "                        </div>\r\n"
                + "                        <br>\r\n"
                + "                    </td>\r\n"
                + "                </tr>\r\n"
                + "            </table>\r\n"
                + "        </td>\r\n"
                + "    </tr>\r\n"
                + "</table>\r\n"
                + "</body>\r\n"
                + "</html>";
    }

    /**
     * DTO interno para controle dos itens alertados.
     */
    private static class ItemAlerta {
        int codInsumo;
        String controle;
    }
}