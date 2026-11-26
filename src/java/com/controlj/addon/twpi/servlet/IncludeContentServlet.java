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

        // Параметр page=board отдаёт простое табло (только плашки помещений).
        String page = request.getParameter("page");
        String target = "/index.html";
        if (page != null && page.trim().equalsIgnoreCase("board")) {
            target = "/board.jsp";
        }

        // Период можно передать параметром days (число суток).
        String query = "?embed=1";
        String days = request.getParameter("days");
        if (days != null && days.trim().matches("\\d{1,3}")) {
            query += "&days=" + days.trim();
        }

        PrintWriter out = response.getWriter();
        // Размер задаёт сам элемент «Add-on Content»: iframe занимает весь бокс,
        // внутри страница прокручивается. min-height — на случай, если контейнер
        // ещё не получил высоту.
        out.write("<div class=\"twpi-embed\" style=\"width:100%;height:100%;"
                + "min-height:160px;margin:0;overflow:hidden\">");
        out.write("<iframe src=\"" + contextPath + target + query + "\" "
                + "style=\"width:100%;height:100%;border:0;display:block;background:#1b1e22\" "
                + "title=\"TWPI\"></iframe>");
        out.write("</div>");
        out.flush();
    }
}
