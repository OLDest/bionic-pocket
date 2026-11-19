package ru.oldest.bionicpocket;

import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.view.*;
import android.widget.*;

public final class MarkdownView {
    private static int dp(Context c,int n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    private static TextView text(Context c,int color,int size){TextView t=new TextView(c);t.setTextColor(color);t.setTextSize(size);t.setTextIsSelectable(true);t.setLineSpacing(dp(c,3),1);return t;}
    private static CharSequence styled(String source,int accent){
        SpannableStringBuilder value=new SpannableStringBuilder();
        for(MarkdownParser.Run run:MarkdownParser.inline(source)){
            int start=value.length();value.append(run.text);int end=value.length();
            int style=run.style;
            if((style&(MarkdownParser.BOLD|MarkdownParser.ITALIC))!=0)value.setSpan(new StyleSpan((style&MarkdownParser.BOLD)!=0?((style&MarkdownParser.ITALIC)!=0?Typeface.BOLD_ITALIC:Typeface.BOLD):Typeface.ITALIC),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if((style&MarkdownParser.STRIKE)!=0)value.setSpan(new StrikethroughSpan(),start,end,33);
            if((style&MarkdownParser.CODE)!=0){value.setSpan(new TypefaceSpan("monospace"),start,end,33);value.setSpan(new BackgroundColorSpan(0xff304138),start,end,33);}
            if(run.url!=null){value.setSpan(new URLSpan(run.url),start,end,33);value.setSpan(new ForegroundColorSpan(accent),start,end,33);}
        }
        return value;
    }
    public static void add(Context c,LinearLayout parent,String source,int color,int muted,int accent){
        for(MarkdownParser.Block block:MarkdownParser.blocks(source)){
            if(block.type.equals("rule")){View line=new View(c);line.setBackgroundColor(0xff42574a);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(c,1));p.setMargins(0,dp(c,8),0,dp(c,8));parent.addView(line,p);continue;}
            if(block.type.equals("code")||block.type.equals("table")){
                LinearLayout panel=new LinearLayout(c);panel.setOrientation(1);panel.setPadding(dp(c,10),dp(c,6),dp(c,10),dp(c,10));GradientDrawable bg=new GradientDrawable();bg.setColor(0xff101c16);bg.setCornerRadius(dp(c,10));panel.setBackground(bg);
                LinearLayout header=new LinearLayout(c);header.setGravity(Gravity.CENTER_VERTICAL);TextView language=text(c,muted,11);language.setText(block.language.isEmpty()?"Код":block.language);header.addView(language,new LinearLayout.LayoutParams(0,-2,1));
                Button copy=new Button(c);copy.setAllCaps(false);copy.setText("Копировать");copy.setTextSize(11);copy.setTextColor(accent);copy.setBackgroundColor(0x00000000);copy.setMinWidth(0);copy.setMinimumWidth(0);copy.setMinHeight(0);copy.setMinimumHeight(0);copy.setPadding(dp(c,8),0,0,0);copy.setOnClickListener(v->{((android.content.ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Bionic code",block.text));Toast.makeText(c,"Скопировано",Toast.LENGTH_SHORT).show();});header.addView(copy,new LinearLayout.LayoutParams(-2,dp(c,32)));panel.addView(header);
                HorizontalScrollView scroll=new HorizontalScrollView(c);scroll.setHorizontalScrollBarEnabled(true);TextView code=text(c,color,13);code.setTypeface(Typeface.MONOSPACE);code.setText(block.text);code.setHorizontallyScrolling(true);scroll.addView(code,new android.widget.FrameLayout.LayoutParams(-2,-2));panel.addView(scroll);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,8);p.bottomMargin=dp(c,6);parent.addView(panel,p);continue;
            }
            TextView t=text(c,color,16);t.setText(styled(block.text,accent));t.setLinkTextColor(accent);t.setMovementMethod(LinkMovementMethod.getInstance());
            if(block.type.equals("heading")){t.setTextSize(block.level==1?23:block.level==2?21:block.level==3?19:17);t.setTypeface(null,Typeface.BOLD);}
            if(block.type.equals("quote")){SpannableStringBuilder quote=new SpannableStringBuilder(t.getText());quote.setSpan(new QuoteSpan(accent),0,quote.length(),33);t.setText(quote);t.setTextColor(muted);}
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,block.type.equals("heading")?12:5);p.bottomMargin=dp(c,3);if(block.type.equals("list"))p.leftMargin=dp(c,block.level*10);parent.addView(t,p);
        }
    }
}
