package ar.com.datafix.optimist;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * V49f - Envío de mails por SMTP, para el SMTPSession / MailMessage de GeneXus (MailPdfGenerico: Enviar Presupuesto por Mail).
 * Sin librerías: conexión común, SSL (puerto 465) o STARTTLS; AUTH PLAIN o LOGIN; texto HTML y adjuntos (MIME).
 */
public final class OptimistMail implements Closeable {

    /** Un adjunto: nombre del archivo y su contenido. */
    public record Adjunto(String nombre, byte[] datos) {}

    /** Un destinatario: dirección y nombre (puede ir vacío). */
    public record Destinatario(String direccion, String nombre) {}

    private Socket socket;
    private BufferedReader in;
    private OutputStream out;
    private final String host;
    private final int puerto;
    private final boolean seguro;

    /** seguro: SSL directo si el puerto es 465; si no, STARTTLS (si el servidor lo ofrece). */
    public OptimistMail(String host, int puerto, boolean seguro) {
        this.host = host == null ? "" : host.trim();
        this.puerto = puerto <= 0 ? (seguro ? 465 : 25) : puerto;
        this.seguro = seguro;
    }

    /** Conecta y, si autenticar, inicia sesión con usuario y contraseña. Tira IOException con la respuesta del servidor. */
    public void login(boolean autenticar, String usuario, String clave) throws IOException {
        if (host.isEmpty()) throw new IOException("falta el servidor de mail (SMTP Host)");
        Socket s;
        if (seguro && puerto == 465) {
            s = SSLSocketFactory.getDefault().createSocket();
            s.connect(new InetSocketAddress(host, puerto), 20000);
            ((SSLSocket) s).startHandshake();
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, puerto), 20000);
        }
        s.setSoTimeout(60000);
        abrir(s);
        esperar(220);
        List<String> ext = ehlo();
        if (!(s instanceof SSLSocket) && (seguro || tiene(ext, "STARTTLS")) && tiene(ext, "STARTTLS")) {
            comando("STARTTLS", 220);
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, puerto, true);
            ssl.startHandshake();
            abrir(ssl);
            ext = ehlo();
        } else if (seguro && !(s instanceof SSLSocket)) {
            throw new IOException("el servidor " + host + " no ofrece conexión segura (STARTTLS)");
        }
        if (autenticar && usuario != null && !usuario.isBlank()) {
            String auth = ext.stream().filter(x -> x.toUpperCase(Locale.ROOT).startsWith("AUTH")).findFirst().orElse("AUTH PLAIN LOGIN").toUpperCase(Locale.ROOT);
            if (auth.contains("PLAIN")) {
                String t = Base64.getEncoder().encodeToString(("\0" + usuario + "\0" + (clave == null ? "" : clave)).getBytes(StandardCharsets.UTF_8));
                comando("AUTH PLAIN " + t, 235);
            } else {
                comando("AUTH LOGIN", 334);
                comando(Base64.getEncoder().encodeToString(usuario.getBytes(StandardCharsets.UTF_8)), 334);
                comando(Base64.getEncoder().encodeToString((clave == null ? "" : clave).getBytes(StandardCharsets.UTF_8)), 235);
            }
        }
    }

    /** Envía un mail (la sesión tiene que estar abierta con login). */
    public void enviar(Destinatario remitente, List<Destinatario> para, List<Destinatario> cc, List<Destinatario> cco,
                       String asunto, String html, List<Adjunto> adjuntos) throws IOException {
        if (out == null) throw new IOException("no hay sesión de mail (falta el Login)");
        if (remitente == null || remitente.direccion().isBlank()) throw new IOException("falta la dirección del remitente (Sender.Address)");
        List<Destinatario> todos = new ArrayList<>();
        todos.addAll(para); todos.addAll(cc); todos.addAll(cco);
        todos.removeIf(d -> d.direccion() == null || d.direccion().isBlank());
        if (todos.isEmpty()) throw new IOException("el mail no tiene destinatarios");
        comando("MAIL FROM:<" + remitente.direccion().trim() + ">", 250);
        for (Destinatario d : todos) comando("RCPT TO:<" + d.direccion().trim() + ">", 250, 251);
        comando("DATA", 354);
        escribirCrudo(mensaje(remitente, para, cc, asunto, html, adjuntos));
        comando(".", 250);
    }

    public void logout() throws IOException {
        if (out == null) return;
        try { comando("QUIT", 221); } finally { close(); }
    }

    @Override public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignorado) { }
        socket = null; out = null; in = null;
    }

    // ------------------------------------------------------------------ el mensaje (MIME)

    static String mensaje(Destinatario de, List<Destinatario> para, List<Destinatario> cc, String asunto, String html, List<Adjunto> adjuntos) {
        String limite = "=_optimist_" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder b = new StringBuilder();
        b.append("Date: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n");
        b.append("From: ").append(direccion(de)).append("\r\n");
        if (!para.isEmpty()) b.append("To: ").append(lista(para)).append("\r\n");
        if (!cc.isEmpty()) b.append("Cc: ").append(lista(cc)).append("\r\n");
        b.append("Subject: ").append(encabezado(asunto == null ? "" : asunto)).append("\r\n");
        b.append("Message-ID: <").append(UUID.randomUUID()).append("@optimist>\r\n");
        b.append("MIME-Version: 1.0\r\n");
        b.append("Content-Type: multipart/mixed; boundary=\"").append(limite).append("\"\r\n\r\n");
        b.append("--").append(limite).append("\r\n");
        b.append("Content-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
        b.append(base64(cuerpoHtml(html).getBytes(StandardCharsets.UTF_8)));
        for (Adjunto a : adjuntos) {
            String n = encabezado(a.nombre());
            b.append("--").append(limite).append("\r\n");
            b.append("Content-Type: ").append(tipo(a.nombre())).append("; name=\"").append(n).append("\"\r\n");
            b.append("Content-Transfer-Encoding: base64\r\n");
            b.append("Content-Disposition: attachment; filename=\"").append(n).append("\"\r\n\r\n");
            b.append(base64(a.datos()));
        }
        b.append("--").append(limite).append("--\r\n");
        return b.toString();
    }

    /** Texto sin etiquetas HTML: los Enter pasan a <br> (como se ve en GeneXus con el NewLine()). */
    static String cuerpoHtml(String t) {
        if (t == null) return "";
        if (t.contains("<")) return t.replace("\r\n", "\n").replace("\n", "<br>\n");
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r\n", "\n").replace("\n", "<br>\n");
    }

    private static String tipo(String nombre) {
        String n = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".txt") || n.endsWith(".csv")) return "text/plain";
        return "application/octet-stream";
    }

    private static String lista(List<Destinatario> ds) {
        StringJoiner j = new StringJoiner(", ");
        for (Destinatario d : ds) if (d.direccion() != null && !d.direccion().isBlank()) j.add(direccion(d));
        return j.toString();
    }

    private static String direccion(Destinatario d) {
        String a = d.direccion().trim();
        String n = d.nombre() == null ? "" : d.nombre().trim();
        return n.isEmpty() || n.equalsIgnoreCase(a) ? "<" + a + ">" : encabezado(n) + " <" + a + ">";
    }

    /** RFC 2047: un texto con acentos en un encabezado. */
    static String encabezado(String t) {
        boolean ascii = t.chars().allMatch(c -> c >= 32 && c < 127 && c != '"');
        return ascii ? t : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(t.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String base64(byte[] b) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(b) + "\r\n";
    }

    // ------------------------------------------------------------------ el diálogo con el servidor

    private void abrir(Socket s) throws IOException {
        socket = s;
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
        out = new BufferedOutputStream(s.getOutputStream());
    }

    private List<String> ehlo() throws IOException {
        String yo;
        try { yo = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException ex) { yo = "optimist"; }
        escribir("EHLO " + yo);
        List<String> r = respuesta();
        int codigo = codigo(r);
        if (codigo != 250) {
            escribir("HELO " + yo);
            r = respuesta();
            if (codigo(r) != 250) throw new IOException("el servidor rechazó el saludo: " + String.join(" ", r));
        }
        List<String> ext = new ArrayList<>();
        for (String x : r) ext.add(x.length() > 4 ? x.substring(4).trim() : "");
        return ext;
    }

    private static boolean tiene(List<String> ext, String x) {
        for (String e : ext) if (e.toUpperCase(Locale.ROOT).startsWith(x)) return true;
        return false;
    }

    private void comando(String c, int... esperados) throws IOException {
        escribir(c);
        List<String> r = respuesta();
        int k = codigo(r);
        for (int e : esperados) if (k == e) return;
        // el usuario y la contraseña no van al mensaje de error
        String mostrado = c.startsWith("AUTH") || Arrays.stream(esperados).anyMatch(e -> e == 235 || e == 334) ? "inicio de sesión (AUTH)" : c;
        throw new IOException(mostrado + ": " + String.join(" ", r));
    }

    private void esperar(int esperado) throws IOException {
        List<String> r = respuesta();
        if (codigo(r) != esperado) throw new IOException("el servidor respondió: " + String.join(" ", r));
    }

    private void escribir(String linea) throws IOException {
        out.write((linea + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** El cuerpo del DATA: los renglones que empiezan con '.' se duplican (RFC 5321). */
    private void escribirCrudo(String texto) throws IOException {
        StringBuilder b = new StringBuilder();
        for (String l : texto.split("\r\n", -1)) {
            if (l.startsWith(".")) b.append('.');
            b.append(l).append("\r\n");
        }
        if (b.length() >= 4 && b.substring(b.length() - 4).equals("\r\n\r\n")) b.setLength(b.length() - 2);
        out.write(b.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private List<String> respuesta() throws IOException {
        List<String> r = new ArrayList<>();
        while (true) {
            String l = in.readLine();
            if (l == null) throw new IOException("el servidor de mail cortó la conexión");
            r.add(l);
            if (l.length() < 4 || l.charAt(3) != '-') return r;
        }
    }

    private static int codigo(List<String> r) {
        try { return Integer.parseInt(r.get(r.size() - 1).substring(0, 3)); } catch (RuntimeException ex) { return -1; }
    }
}
