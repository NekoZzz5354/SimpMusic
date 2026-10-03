package com.simpmusic.client;

public final class TextUtil {
   private TextUtil() {
   }

   public static String sanitize(String input) {
      if (input == null) {
         return "";
      }

      StringBuilder sb = new StringBuilder(input.length());

      for (int i = 0; i < input.length(); i++) {
         char c = input.charAt(i);
         if (c != 167 && c >= ' ' && (c < 127 || c > 159) && c != 8203 && c != 8204 && c != 8205 && c != '\ufeff') {
            sb.append(c);
         }
      }

      String s = sb.toString().trim();
      return s.length() > 256 ? s.substring(0, 256) : s;
   }
}
