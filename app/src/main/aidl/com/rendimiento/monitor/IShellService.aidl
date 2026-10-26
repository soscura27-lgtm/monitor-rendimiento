package com.rendimiento.monitor;

interface IShellService {
    // Requerido por Shizuku: el servidor llama a este metodo para cerrar el proceso.
    void destroy() = 16777114;

    // Lee varios archivos de texto (por ejemplo /proc/stat o /sys/...) y devuelve su contenido.
    String[] readFiles(in String[] paths) = 1;

    // Lista los nombres de una carpeta, separados por salto de linea.
    String listDir(String path) = 2;

    // Ejecuta un comando (sin shell) con permisos de shell y devuelve su salida (maximo 8 s).
    String exec(in String[] args) = 3;

    // Tabla de procesos: una linea por proceso con
    // "pid|uid|utime|stime|rssKb|anonKb|shmemKb|swapKb|nombre".
    String procTable() = 4;

    // Sockets de las apps (tcp, tcp6, udp, udp6): una linea por socket con
    // "uid|proto|state|remoteIp|remotePort|localPort". Las IPv4 dentro de IPv6 (::ffff:a.b.c.d)
    // salen como IPv4. Los UDP sin conexion salen con puerto remoto 0.
    String sockets() = 5;

    // Tabla de conexiones del kernel (nf_conntrack): "proto|srcPort|dstIp|dstPort" por flujo.
    String conntrack() = 6;

    // FPS de un task (Android 13+): registra un listener en WindowManager.
    // Devuelve "" si quedo registrado o el texto del error.
    String watchTaskFps(int taskId) = 7;

    // Ultimo reporte de FPS del task: "taskId|fps|msDesdeElReporte|reportes|repetidos" o "".
    String taskFps() = 8;

    // Ejecuta una linea con /system/bin/sh -c, con limite de tiempo en milisegundos.
    String sh(String command, int timeoutMs) = 9;
}
