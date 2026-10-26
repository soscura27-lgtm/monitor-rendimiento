package com.rendimiento.monitor;

interface IShellService {
    // Requerido por Shizuku: el servidor llama a este metodo para cerrar el proceso.
    void destroy() = 16777114;

    // Lee varios archivos de texto (por ejemplo /proc/stat o /sys/...) y devuelve su contenido.
    String[] readFiles(in String[] paths) = 1;

    // Lista los nombres de una carpeta, separados por salto de linea.
    String listDir(String path) = 2;

    // Ejecuta un comando (sin shell) con permisos de shell y devuelve su salida.
    String exec(in String[] args) = 3;

    // Tabla de procesos: una linea por proceso con "pid|uid|utime|stime|rssKb|bytesRx|bytesTx|cmdline".
    String procTable() = 4;

    // Conexiones TCP/UDP de todos los UIDs: una linea por socket con "uid|proto|state|remoteIp|remotePort".
    String sockets() = 5;
}
