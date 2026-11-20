package com.jarvis.assistant.privilege;

interface IPrivilegedService {
    void destroy() = 16777114;
    String runCommand(String command, int timeoutMs) = 1;
    String getIdentity() = 2;
}
