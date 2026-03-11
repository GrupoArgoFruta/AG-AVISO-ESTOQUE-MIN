package br.com.argo.estoquemin.service;

import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.wrapper.JapeFactory;
import br.com.sankhya.jape.wrapper.JapeWrapper;
import br.com.sankhya.modelcore.MGEModelException;
import com.sankhya.util.TimeUtils;

import java.math.BigDecimal;

/**
 * Service responsavel por enviar notificacoes de alerta
 * de estoque baixo para os usuarios do Sankhya via AvisoSistema.
 *
 * @author Natan - Argo Fruta
 * @version 1.0
 * @since 2026-03-02
 */
public class NotificaUserService {

    /**
     * Envia notificacao no portal Sankhya via AvisoSistema (TSIAVI).
     *
     * @param obs    Corpo da notificacao (HTML)
     * @param titulo Titulo da notificacao
     */

    // Lista de usuários que recebem o alerta
    private static final int[] USUARIOS_ALERTA = {29, 180, 18};

    public void notifUsu(String obs, String titulo) throws MGEModelException {
        JapeWrapper avisoDAO = JapeFactory.dao("AvisoSistema");

        for (int codUsu : USUARIOS_ALERTA) {
            try {
                @SuppressWarnings("unused")
                DynamicVO avisoVO = (DynamicVO) avisoDAO.create()
                        .set("NUAVISO", null)
                        .set("CODUSUREMETENTE", BigDecimal.valueOf(0))
                        .set("CODUSU", BigDecimal.valueOf(codUsu))
                        .set("TITULO", titulo)
                        .set("DESCRICAO", obs)
                        .set("DHCRIACAO", TimeUtils.getNow())
                        .set("IDENTIFICADOR", "ESTOQUE_MIN_UVA")
                        .set("IMPORTANCIA", BigDecimal.valueOf(3))
                        .set("SOLUCAO", null)
                        .set("TIPO", "P")
                        .save();

            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Erro ao notificar usuario " + codUsu + ": " + e.getMessage());
            }
        }
    }
}