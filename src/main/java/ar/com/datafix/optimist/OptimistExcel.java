package ar.com.datafix.optimist;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * V26 - Genera un .xlsx a partir de filas y columnas. Genérico: no sabe de ninguna TRN.
 * Números como números (se pueden sumar), fechas como fechas, encabezado en negrita,
 * primera fila congelada y autofiltro.
 */
public final class OptimistExcel {
    private OptimistExcel() {}

    /** titulo: encabezado de la columna. valor: dato de la fila. formato: formato Excel para números (o null). */
    public record Columna<T>(String titulo, Function<T, ?> valor, String formato) {}

    public static <T> byte[] generar(String hoja, List<Columna<T>> columnas, List<T> filas) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet(WorkbookUtil.createSafeSheetName(hoja == null || hoja.isBlank() ? "Datos" : hoja));
            DataFormat df = wb.createDataFormat();

            Font negrita = wb.createFont();
            negrita.setBold(true);
            CellStyle cabecera = wb.createCellStyle();
            cabecera.setFont(negrita);
            cabecera.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            cabecera.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            cabecera.setBorderBottom(BorderStyle.THIN);

            CellStyle fecha = wb.createCellStyle();
            fecha.setDataFormat(df.getFormat("dd/mm/yyyy"));
            CellStyle fechaHora = wb.createCellStyle();
            fechaHora.setDataFormat(df.getFormat("dd/mm/yyyy hh:mm"));

            int n = columnas.size();
            CellStyle[] numeros = new CellStyle[n];
            int[] anchos = new int[n];
            Row r0 = sh.createRow(0);
            for (int i = 0; i < n; i++) {
                Columna<T> c = columnas.get(i);
                Cell celda = r0.createCell(i);
                celda.setCellValue(c.titulo());
                celda.setCellStyle(cabecera);
                anchos[i] = c.titulo().length() + 3;   // lugar para la flechita del autofiltro
                if (c.formato() != null) {
                    numeros[i] = wb.createCellStyle();
                    numeros[i].setDataFormat(df.getFormat(c.formato()));
                }
            }

            int fila = 1;
            for (T item : filas) {
                Row r = sh.createRow(fila++);
                for (int i = 0; i < n; i++) {
                    Object v = columnas.get(i).valor().apply(item);
                    if (v == null) continue;
                    Cell celda = r.createCell(i);
                    int largo;
                    if (v instanceof Number num) {
                        celda.setCellValue(num.doubleValue());
                        if (numeros[i] != null) celda.setCellStyle(numeros[i]);
                        largo = num.toString().length() + 3;
                    } else if (v instanceof LocalDate d) {
                        celda.setCellValue(d);
                        celda.setCellStyle(fecha);
                        largo = 10;
                    } else if (v instanceof LocalDateTime t) {
                        celda.setCellValue(t);
                        celda.setCellStyle(fechaHora);
                        largo = 16;
                    } else if (v instanceof java.util.Date d) {
                        celda.setCellValue(d);
                        celda.setCellStyle(fechaHora);
                        largo = 16;
                    } else if (v instanceof Boolean b) {
                        celda.setCellValue(b ? "Sí" : "No");
                        largo = 2;
                    } else {
                        String s = String.valueOf(v).stripTrailing();
                        celda.setCellValue(s);
                        largo = s.length();
                    }
                    anchos[i] = Math.max(anchos[i], largo);
                }
            }

            sh.createFreezePane(0, 1);
            if (n > 0) sh.setAutoFilter(new CellRangeAddress(0, Math.max(0, fila - 1), 0, n - 1));
            for (int i = 0; i < n; i++) sh.setColumnWidth(i, Math.min(60, Math.max(8, anchos[i] + 2)) * 256);

            wb.write(out);
            return out.toByteArray();
        }
    }
}
