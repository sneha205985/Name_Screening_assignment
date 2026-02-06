package com.example.screening;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = 5050;
        ScreeningService service = new ScreeningService(port);
        service.start();
    }
}