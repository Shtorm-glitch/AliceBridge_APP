package dev.alicebridge.app;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class ScreenUi {
    static final int BG=0xff090d17,PANEL=0xff141a29,TEXT=0xffedf0fc,MUTED=0xff909ab3,ACCENT=0xffb6a1ff,GREEN=0xff72e1bc,WARN=0xffffba9e;
    static int dp(Activity a,int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    static LinearLayout column(Activity a){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Activity a){LinearLayout l=new LinearLayout(a);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static GradientDrawable bg(int color,int radius,Activity a){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(a,radius));return b;}
    static TextView text(Activity a,String value,int size,int color){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(a,3),1);return t;}
    static TextView button(Activity a,String value,Runnable action,boolean primary){TextView b=text(a,value,15,primary?BG:ACCENT);b.setTypeface(null,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(a,50));b.setPadding(dp(a,14),dp(a,12),dp(a,14),dp(a,12));b.setBackground(bg(primary?ACCENT:0xff22283a,16,a));b.setOnClickListener(v->action.run());return b;}
    static LinearLayout.LayoutParams space(Activity a,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(a,top);return p;}
    static LinearLayout card(Activity a){LinearLayout l=column(a);l.setPadding(dp(a,18),dp(a,16),dp(a,18),dp(a,16));l.setBackground(bg(PANEL,22,a));return l;}
    static EditText field(Activity a,String hint,boolean secret){EditText e=new EditText(a);e.setSingleLine(true);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(15);e.setHint(hint);e.setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14));e.setBackground(bg(PANEL,14,a));e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(secret?android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));return e;}
    static FrameLayout passwordField(Activity a,EditText input){
        FrameLayout frame=new FrameLayout(a);frame.setBackground(bg(PANEL,14,a));
        input.setBackgroundColor(Color.TRANSPARENT);input.setPadding(dp(a,16),dp(a,14),dp(a,64),dp(a,14));
        frame.addView(input,new FrameLayout.LayoutParams(-1,-2));
        ImageButton eye=new ImageButton(a);eye.setBackgroundColor(Color.TRANSPARENT);eye.setImageResource(R.drawable.ic_eye);
        eye.setContentDescription("Показать пароль");eye.setPadding(dp(a,12),dp(a,12),dp(a,12),dp(a,12));
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(dp(a,48),dp(a,48),Gravity.END|Gravity.CENTER_VERTICAL);position.rightMargin=dp(a,4);frame.addView(eye,position);
        eye.setOnClickListener(v->{
            int start=input.getSelectionStart(),end=input.getSelectionEnd();boolean visible=!eye.isSelected();eye.setSelected(visible);
            input.setTransformationMethod(visible?android.text.method.HideReturnsTransformationMethod.getInstance():android.text.method.PasswordTransformationMethod.getInstance());
            if(start>=0&&end>=0)input.setSelection(Math.min(start,input.length()),Math.min(end,input.length()));
            eye.setImageResource(visible?R.drawable.ic_eye_off:R.drawable.ic_eye);eye.setContentDescription(visible?"Скрыть пароль":"Показать пароль");
        });
        return frame;
    }
    static LinearLayout root(Activity a,String title,Runnable back){
        a.getWindow().setStatusBarColor(BG);a.getWindow().setNavigationBarColor(BG);
        LinearLayout root=column(a);root.setBackgroundColor(BG);root.setOnApplyWindowInsetsListener((v,insets)->{Insets b=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(a,22),b.top+dp(a,10),dp(a,22),b.bottom+dp(a,14));return insets;});
        LinearLayout header=row(a);ImageButton arrow=new ImageButton(a);arrow.setImageResource(R.drawable.ic_arrow_back);arrow.setContentDescription("Назад");arrow.setScaleType(ImageView.ScaleType.CENTER);arrow.setPadding(dp(a,12),dp(a,12),dp(a,12),dp(a,12));arrow.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30b6a1ff),bg(0xff22283a,16,a),null));arrow.setOnClickListener(v->back.run());header.addView(arrow,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));
        TextView label=text(a,title,24,TEXT);label.setTypeface(null,Typeface.BOLD);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(a,14);header.addView(label,p);root.addView(header);a.setContentView(root);return root;
    }
}
