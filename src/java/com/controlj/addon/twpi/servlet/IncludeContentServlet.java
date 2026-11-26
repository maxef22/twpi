package com.controlj.addon.twpi.servlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Встраивание аддона в интерфейс WebCTRL.
 *
 * <p>Когда аддон открывают «средством отображения аддонов», WebCTRL делает
 * GET {@code /twpi/include_content} и вставляет ответ в свою страницу, выполняя
 * при этом inline-скрипты ({@code updatehtml.js}, AddonSupport). Своего
 * эндпоинта у нас не было — отсюда ошибка «resource [/twpi/include_content]
 * is not available».</p>
 *
 * <p>Отдаём iframe со обычной страницей аддона: так наш тёмный CSS не действует
 * на интерфейс WebCTRL, идентификаторы элементов не пересекаются, а переходы
 * внутри аддона (карточка помещения, настройки) остаются в этом же iframe.</p>
 */
public class IncludeContentServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");

        String path = request.getServletPath();
        if (path != null && path.endsWith("include_script")) {
            // WebCTRL запрашивает скрипт аддона отдельно — отдаём пустой.
            response.setContentType("application/javascript; charset=UTF-8");
            PrintWriter script = response.getWriter();
            script.write("/* TWPI: отдельный include_script не используется */");
            script.flush();
            return;
        }

        response.setContentType("text/html; charset=UTF-8");
        String contextPath = request.getContextPath();
        PrintWriter out = response.getWriter();
        out.write("<div class=\"twpi-embed\" style=\"width:100%\">");
        out.write("<iframe src=\"" + contextPath + "/index.html?embed=1\" "
                + "style=\"width:100%;height:calc(100vh - 120px);min-height:640px;"
                + "border:0;display:block\" "
                + "title=\"TWPI\"></iframe>");
        out.write("</div>");
        out.flush();
    }
}
