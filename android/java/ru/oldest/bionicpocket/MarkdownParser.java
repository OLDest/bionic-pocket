package ru.oldest.bionicpocket;

import java.util.*;
import java.util.regex.*;

/** Small native Markdown parser. Raw HTML is always text; code is never parsed. */
public final class MarkdownParser {
    public static final int BOLD=1, ITALIC=2, STRIKE=4, CODE=8;
    public static final class Block {
        public final String type,text,language;
        public final int level;
        Block(String type,String text,int level,String language){this.type=type;this.text=text;this.level=level;this.language=language;}
    }
    public static final class Run {
        public final String text,url;
        public final int style;
        Run(String text,int style,String url){this.text=text;this.style=style;this.url=url;}
    }
    private static final Pattern HEADING=Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)(?:\\s+#+)?$");
    private static final Pattern LIST=Pattern.compile("^(\\s*)(?:[-+*]|(\\d+)[.)])\\s+(.+)$");
    private static boolean fence(String s){String t=s.trim();return t.startsWith("```")||t.startsWith("~~~");}
    private static boolean rule(String s){return s.trim().matches("(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,}");}
    private static boolean start(String s){return fence(s)||rule(s)||HEADING.matcher(s).matches()||LIST.matcher(s).matches()||s.trim().startsWith(">");}
    public static List<Block> blocks(String source){
        String[] lines=source.replace("\r\n","\n").replace('\r','\n').split("\n",-1);
        List<Block> out=new ArrayList<>();
        for(int i=0;i<lines.length;){
            String line=lines[i];
            if(line.trim().isEmpty()){i++;continue;}
            if(fence(line)){
                String trimmed=line.trim();char marker=trimmed.charAt(0);int width=0;while(width<trimmed.length()&&trimmed.charAt(width)==marker)width++;
                String language=trimmed.substring(width).trim();StringBuilder code=new StringBuilder();i++;
                while(i<lines.length){String close=lines[i].trim();int n=0;while(n<close.length()&&close.charAt(n)==marker)n++;if(n>=width&&close.substring(n).trim().isEmpty()){i++;break;}if(code.length()>0)code.append('\n');code.append(lines[i++]);}
                out.add(new Block("code",code.toString(),0,language));continue;
            }
            Matcher heading=HEADING.matcher(line);
            if(heading.matches()){out.add(new Block("heading",heading.group(2),heading.group(1).length(),""));i++;continue;}
            if(rule(line)){out.add(new Block("rule","",0,""));i++;continue;}
            if(i+1<lines.length&&line.contains("|")&&lines[i+1].trim().matches("[| :\\-]+")&&lines[i+1].contains("---")){
                List<String[]> rows=new ArrayList<>();rows.add(cells(line));i+=2;
                while(i<lines.length&&lines[i].contains("|")&&!lines[i].trim().isEmpty())rows.add(cells(lines[i++]));
                int columns=0;for(String[] row:rows)columns=Math.max(columns,row.length);
                int[] widths=new int[columns];for(String[] row:rows)for(int c=0;c<row.length;c++)widths[c]=Math.min(40,Math.max(widths[c],row[c].length()));
                StringBuilder table=new StringBuilder();for(int r=0;r<rows.size();r++){if(r>0)table.append('\n');for(int c=0;c<columns;c++){if(c>0)table.append(" | ");String value=c<rows.get(r).length?rows.get(r)[c]:"";table.append(value);for(int p=value.length();p<widths[c];p++)table.append(' ');}}
                out.add(new Block("table",table.toString(),0,"Таблица"));continue;
            }
            if(line.trim().startsWith(">")){
                StringBuilder quote=new StringBuilder();while(i<lines.length&&lines[i].trim().startsWith(">")){if(quote.length()>0)quote.append('\n');quote.append(lines[i++].trim().replaceFirst("^> ?",""));}out.add(new Block("quote",quote.toString(),0,""));continue;
            }
            Matcher list=LIST.matcher(line);
            if(list.matches()){
                String value=list.group(3),prefix=list.group(2)==null?"• ":list.group(2)+". ";
                if(value.startsWith("[ ] ")){prefix="☐ ";value=value.substring(4);}else if(value.startsWith("[x] ")||value.startsWith("[X] ")){prefix="☑ ";value=value.substring(4);}
                out.add(new Block("list",prefix+value,Math.min(6,list.group(1).length()/2),""));i++;continue;
            }
            StringBuilder paragraph=new StringBuilder(line);i++;
            while(i<lines.length&&!lines[i].trim().isEmpty()&&!start(lines[i])){if(i+1<lines.length&&lines[i].contains("|")&&lines[i+1].trim().matches("[| :\\-]+"))break;paragraph.append('\n').append(lines[i++]);}
            out.add(new Block("text",paragraph.toString(),0,""));
        }
        return out;
    }
    private static String[] cells(String line){String s=line.trim();if(s.startsWith("|"))s=s.substring(1);if(s.endsWith("|"))s=s.substring(0,s.length()-1);String[] values=s.split("\\|",-1);for(int i=0;i<values.length;i++){StringBuilder text=new StringBuilder();for(Run run:inline(values[i].trim()))text.append(run.text);values[i]=text.toString();}return values;}
    public static List<Run> inline(String source){List<Run> out=new ArrayList<>();parseInline(source,0,0,out);return out;}
    private static void append(List<Run> out,String text,int style,String url){if(!text.isEmpty())out.add(new Run(text,style,url));}
    private static boolean safeUrl(String url){return (url.startsWith("https://")||url.startsWith("http://"))&&!url.matches(".*[\\s<>].*");}
    private static void parseInline(String s,int style,int depth,List<Run> out){
        if(depth>8){append(out,s,style,null);return;}
        StringBuilder plain=new StringBuilder();
        for(int i=0;i<s.length();){
            char ch=s.charAt(i);
            if(ch=='\\'&&i+1<s.length()&&"\\`*_{}[]()#+-.!>~".indexOf(s.charAt(i+1))>=0){plain.append(s.charAt(i+1));i+=2;continue;}
            if(ch=='`'){
                int n=1;while(i+n<s.length()&&s.charAt(i+n)=='`')n++;String marker=s.substring(i,i+n);int end=s.indexOf(marker,i+n);
                if(end>=0){append(out,plain.toString(),style,null);plain.setLength(0);append(out,s.substring(i+n,end),style|CODE,null);i=end+n;continue;}
            }
            boolean image=ch=='!'&&i+1<s.length()&&s.charAt(i+1)=='[';int open=image?i+1:i;
            if(s.charAt(open)=='['){int label=s.indexOf("](",open+1);int end=label<0?-1:s.indexOf(')',label+2);
                if(end>label){String url=s.substring(label+2,end).trim();if(safeUrl(url)){append(out,plain.toString(),style,null);plain.setLength(0);append(out,(image?"Изображение: ":"")+s.substring(open+1,label),style,url);i=end+1;continue;}}
            }
            if(s.startsWith("https://",i)||s.startsWith("http://",i)){
                int end=i;while(end<s.length()&&!Character.isWhitespace(s.charAt(end))&&"<>".indexOf(s.charAt(end))<0)end++;
                while(end>i&&".,;!?)".indexOf(s.charAt(end-1))>=0)end--;
                String url=s.substring(i,end);if(safeUrl(url)){append(out,plain.toString(),style,null);plain.setLength(0);append(out,url,style,url);i=end;continue;}
            }
            String marker=null;int flag=0;
            if(s.startsWith("**",i)||s.startsWith("__",i)){marker=s.substring(i,i+2);flag=BOLD;}
            else if(s.startsWith("~~",i)){marker="~~";flag=STRIKE;}
            else if(ch=='*'||ch=='_'){if(ch!='_'||i==0||!Character.isLetterOrDigit(s.charAt(i-1))){marker=String.valueOf(ch);flag=ITALIC;}}
            if(marker!=null){int end=s.indexOf(marker,i+marker.length());if(end>i+marker.length()){append(out,plain.toString(),style,null);plain.setLength(0);parseInline(s.substring(i+marker.length(),end),style|flag,depth+1,out);i=end+marker.length();continue;}}
            plain.append(ch);i++;
        }
        append(out,plain.toString(),style,null);
    }
}
