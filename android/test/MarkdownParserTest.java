import ru.oldest.bionicpocket.MarkdownParser;
import java.util.*;
public final class MarkdownParserTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String plain(List<MarkdownParser.Run> runs){StringBuilder b=new StringBuilder();for(MarkdownParser.Run run:runs)b.append(run.text);return b.toString();}
    public static void main(String[] args){
        List<MarkdownParser.Block> blocks=MarkdownParser.blocks("# Title\n\n**bold** and *italic*\n\n- first\n1. second\n> quote\n\n```go\nfmt.Println(\"**raw**\")\n```\n");
        check(blocks.size()==6,"block types");check(blocks.get(0).type.equals("heading"),"heading");check(blocks.get(2).text.equals("• first"),"bullet");check(blocks.get(3).text.equals("1. second"),"number");check(blocks.get(4).type.equals("quote"),"quote");check(blocks.get(5).text.equals("fmt.Println(\"**raw**\")"),"code remains raw");
        List<MarkdownParser.Run> runs=MarkdownParser.inline("**bold** *italic* ~~gone~~ `x_y` [Go](https://go.dev)");
        check(plain(runs).equals("bold italic gone x_y Go"),"inline markers removed");check(runs.stream().anyMatch(r->(r.style&MarkdownParser.BOLD)!=0),"bold style");check(runs.stream().anyMatch(r->(r.style&MarkdownParser.CODE)!=0&&r.text.equals("x_y")),"inline code");check(runs.stream().anyMatch(r->"https://go.dev".equals(r.url)),"real link");
        check(plain(MarkdownParser.inline("snake_case and \\*literal\\* <b>html</b>")).equals("snake_case and *literal* <b>html</b>"),"escapes and no HTML execution");
        check(MarkdownParser.inline("[bad](javascript:alert(1))").stream().noneMatch(r->r.url!=null),"unsafe scheme not clickable");
        check(MarkdownParser.inline("See https://go.dev/doc/.").stream().anyMatch(r->"https://go.dev/doc/".equals(r.url)),"plain URL punctuation");
        List<MarkdownParser.Block> table=MarkdownParser.blocks("| Name | Value |\n| --- | --- |\n| **Go** | 42 |");check(table.size()==1&&table.get(0).type.equals("table"),"table");check(table.get(0).text.contains("Go")&&!table.get(0).text.contains("**"),"table cells");
        check(MarkdownParser.blocks("```text\nunfinished").get(0).text.equals("unfinished"),"unclosed code fence");
        check(MarkdownParser.blocks("- [x] done\n- [ ] todo").get(0).text.equals("☑ done"),"task lists");
        check(MarkdownParser.inline("**bold _nested_**").stream().anyMatch(r->r.text.equals("nested")&&r.style==3),"nested emphasis");
        System.out.println("Markdown parser checks passed");
    }
}
