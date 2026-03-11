package br.com.argo.estoquemin.service;

import br.com.sankhya.jape.core.JapeSession;
import br.com.sankhya.jape.wrapper.JapeFactory;
import br.com.sankhya.jape.wrapper.JapeWrapper;
import br.com.sankhya.modelcore.util.DynamicEntityNames;
import com.sankhya.util.BigDecimalUtil;

import java.math.BigDecimal;

/**
 * Service responsavel por enviar emails de alerta
 * de estoque baixo via fila de mensagens do Sankhya (TMDFMG).
 *
 * @author Natan - Argo Fruta
 * @version 1.0
 * @since 2026-03-02
 */
public class EnvioEmailService {

    // TODO: Configurar email(s) destinatario(s) do alerta
    private static final String EMAIL_DESTINATARIO = "almoxarifado.compras@argofruta.com";

    /**
     * Envia email via fila de mensagens do Sankhya.
     *
     * @param titulo   Assunto do email
     * @param mensagem Corpo do email (HTML)
     */
    public void enviarEmail(String titulo, String mensagem) throws Exception {
        JapeSession.SessionHandle hnd = null;
        try {
            hnd = JapeSession.open();
            JapeWrapper filaMsgDAO = JapeFactory.dao(DynamicEntityNames.FILA_MSG);
            filaMsgDAO.create()
                    .set("EMAIL", EMAIL_DESTINATARIO)
                    .set("CODCON", BigDecimal.ZERO)
                    .set("STATUS", "Pendente")
                    .set("TIPOENVIO", "E")
                    .set("MAXTENTENVIO", BigDecimalUtil.valueOf(3))
                    .set("ASSUNTO", titulo)
                    .set("MENSAGEM", mensagem.toCharArray())
                    .save();

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            JapeSession.close(hnd);
        }
    }
}