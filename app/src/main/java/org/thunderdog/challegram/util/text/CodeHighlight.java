/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.util.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A bounded, linear scan for the common code languages supported by rich messages. */
public final class CodeHighlight {
  public static final int PLAIN = 0;
  public static final int KEYWORD = 1;
  public static final int OPERATOR = 2;
  public static final int CONSTANT = 3;
  public static final int STRING = 4;
  public static final int NUMBER = 5;
  public static final int COMMENT = 6;
  public static final int FUNCTION = 7;

  private static final Set<String> LANGUAGES = words(
    "java kotlin javascript typescript python json c cpp csharp go rust swift php ruby " +
    "shell sql css html xml yaml toml");
  private static final Set<String> KEYWORDS = words(
    "abstract as assert async await auto boolean bool break byte case catch char class " +
    "const continue constexpr def default delete do double elif else enum except export " +
    "extends extern final finally float fn for foreach from fun function if implements " +
    "import in inline instanceof int interface internal is lambda let long module mut " +
    "namespace native new nonlocal object of open operator out override package pass " +
    "private protected public raise readonly record ref return sealed short signed " +
    "sizeof static strictfp string struct super suspend switch synchronized template " +
    "this throw throws trait transient try type typedef typeof union unsigned use using " +
    "val var virtual void volatile when where while with yield and or not select insert " +
    "update into values set delete create table drop alter join left right inner outer " +
    "on group by order asc desc having limit distinct union all null true false none " +
    "then fi done esac export local echo");
  private static final Set<String> CONSTANTS = words("true false null none nil undefined nan");

  private CodeHighlight () { }

  public static final class Token {
    public final int start;
    public final int end;
    public final int type;

    Token (int start, int end, int type) {
      this.start = start;
      this.end = end;
      this.type = type;
    }
  }

  private static Set<String> words (String value) {
    return new HashSet<>(Arrays.asList(value.split(" ")));
  }

  public static List<Token> tokenize (String code, String language) {
    ArrayList<Token> tokens = new ArrayList<>();
    String normalized = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
    switch (normalized) {
      case "js": normalized = "javascript"; break;
      case "ts": normalized = "typescript"; break;
      case "py": normalized = "python"; break;
      case "kt": normalized = "kotlin"; break;
      case "c++": normalized = "cpp"; break;
      case "c#": case "cs": normalized = "csharp"; break;
      case "bash": case "sh": case "zsh": normalized = "shell"; break;
      case "yml": normalized = "yaml"; break;
    }
    // Keep unknown languages readable, and avoid building thousands of text entities
    // for exceptionally large blocks on the UI thread.
    if (!LANGUAGES.contains(normalized) || code.length() > 65536) {
      append(tokens, 0, code.length(), PLAIN);
      return tokens;
    }
    boolean hashComments = normalized.equals("python") || normalized.equals("ruby") ||
      normalized.equals("shell") || normalized.equals("yaml") || normalized.equals("toml");
    boolean sql = normalized.equals("sql");
    boolean markup = normalized.equals("html") || normalized.equals("xml");
    boolean slashComments = !hashComments && !sql && !markup && !normalized.equals("json");
    for (int cursor = 0; cursor < code.length();) {
      int start = cursor;
      int type = PLAIN;
      char character = code.charAt(cursor);
      char next = cursor + 1 < code.length() ? code.charAt(cursor + 1) : '\0';
      if (markup && code.startsWith("<!--", cursor)) {
        int end = code.indexOf("-->", cursor + 4);
        cursor = end < 0 ? code.length() : end + 3;
        type = COMMENT;
      } else if ((hashComments && character == '#') ||
          (sql && character == '-' && next == '-') ||
          (slashComments && character == '/' && next == '/')) {
        int end = code.indexOf('\n', cursor);
        cursor = end < 0 ? code.length() : end;
        type = COMMENT;
      } else if ((slashComments || sql) && character == '/' && next == '*') {
        int end = code.indexOf("*/", cursor + 2);
        cursor = end < 0 ? code.length() : end + 2;
        type = COMMENT;
      } else if (character == '\'' || character == '"' || character == '`') {
        boolean triple = cursor + 2 < code.length() && next == character &&
          code.charAt(cursor + 2) == character && normalized.equals("python");
        cursor += triple ? 3 : 1;
        while (cursor < code.length()) {
          if (code.charAt(cursor) == '\\') {
            cursor = Math.min(code.length(), cursor + 2);
          } else if (code.charAt(cursor) == character && (!triple ||
              cursor + 2 < code.length() && code.charAt(cursor + 1) == character &&
                code.charAt(cursor + 2) == character)) {
            cursor += triple ? 3 : 1;
            break;
          } else {
            cursor++;
          }
        }
        type = STRING;
      } else if (Character.isDigit(character) || character == '.' && Character.isDigit(next)) {
        cursor++;
        while (cursor < code.length()) {
          char digit = code.charAt(cursor);
          if (Character.isLetterOrDigit(digit) || digit == '_' || digit == '.' ||
              (digit == '+' || digit == '-') &&
                (code.charAt(cursor - 1) == 'e' || code.charAt(cursor - 1) == 'E')) {
            cursor++;
          } else {
            break;
          }
        }
        type = NUMBER;
      } else if (Character.isJavaIdentifierStart(character)) {
        cursor++;
        while (cursor < code.length() && Character.isJavaIdentifierPart(code.charAt(cursor))) {
          cursor++;
        }
        String identifier = code.substring(start, cursor).toLowerCase(Locale.ROOT);
        if (CONSTANTS.contains(identifier)) {
          type = CONSTANT;
        } else if (KEYWORDS.contains(identifier)) {
          type = KEYWORD;
        } else {
          int after = cursor;
          while (after < code.length() && Character.isWhitespace(code.charAt(after))) after++;
          if (after < code.length() && code.charAt(after) == '(') type = FUNCTION;
        }
      } else {
        cursor++;
        if ("+-*/%=!<>?:&|^~".indexOf(character) >= 0) type = OPERATOR;
      }
      append(tokens, start, cursor, type);
    }
    return tokens;
  }

  private static void append (ArrayList<Token> tokens, int start, int end, int type) {
    if (end <= start) return;
    if (!tokens.isEmpty()) {
      Token previous = tokens.get(tokens.size() - 1);
      if (previous.type == type && previous.end == start) {
        tokens.set(tokens.size() - 1, new Token(previous.start, end, type));
        return;
      }
    }
    tokens.add(new Token(start, end, type));
  }
}
