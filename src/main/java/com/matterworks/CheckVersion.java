package com.matterworks;

public class CheckVersion {
    public static void main(String[] args) {
        System.out.println("Versione Java attiva: " + System.getProperty("java.version"));
        System.out.println("Preview Features attive: " + String.class.isPrimitive()); // Test stupido per vedere se crasha
    }
}