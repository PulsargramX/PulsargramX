#!/usr/bin/env python3
"""Execute the production unequal-corner path builder without Android/Gradle."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Requires JDK')
class BubbleCornerPathTest(unittest.TestCase):
    def test_unequal_media_corners_have_no_outside_points_or_arc_connectors(self):
        source = (ROOT / 'app/src/main/java/org/thunderdog/challegram/tool/DrawAlgorithms.java').read_text()
        start = source.index('public static void buildPath (')
        opening = source.index('{', start)
        depth = 1
        end = opening + 1
        while depth:
            depth += (source[end] == '{') - (source[end] == '}')
            end += 1
        method = source[start:end]
        fixture = r'''
class RectF {
  float left, top, right, bottom;
  RectF() {}
  RectF(float l,float t,float r,float b) {set(l,t,r,b);}
  void set(float l,float t,float r,float b) {left=l;top=t;right=r;bottom=b;}
  float centerX(){return (left+right)/2;}
  float centerY(){return (top+bottom)/2;}
  float width(){return right-left;}
  float height(){return bottom-top;}
}
class Path {
  enum Direction {CW}
  final RectF bounds;
  float x,y;
  Path(RectF bounds){this.bounds=bounds;}
  void moveTo(float x,float y){check(x,y);this.x=x;this.y=y;}
  void lineTo(float x,float y){moveTo(x,y);}
  void check(float x,float y){
    if(x<bounds.left-.001 || x>bounds.right+.001 || y<bounds.top-.001 || y>bounds.bottom+.001)
      throw new AssertionError("Bubble path extends outside its bounds: "+x+","+y);
  }
  void arcTo(RectF r,float start,float sweep){
    double a=Math.toRadians(start);
    float sx=r.centerX()+(float)Math.cos(a)*r.width()/2;
    float sy=r.centerY()+(float)Math.sin(a)*r.height()/2;
    if(Math.abs(x-sx)>.001 || Math.abs(y-sy)>.001)
      throw new AssertionError("Unexpected straight connector into corner arc");
    a=Math.toRadians(start+sweep);
    moveTo(r.centerX()+(float)Math.cos(a)*r.width()/2,
           r.centerY()+(float)Math.sin(a)*r.height()/2);
  }
  void close(){}
  void addCircle(float x,float y,float radius,Direction d){}
  void addRoundRect(RectF r,float x,float y,Direction d){}
}
public class DrawAlgorithms {
  static RectF pathHelper;
  METHOD
  public static void main(String[] args){
    float[][] cases={{28,6,6,28},{6,28,28,6},{28,28,6,28},
                     {28,6,28,28},{0,28,6,0},{28,0,0,6},{28,28,28,28}};
    for(float[] radii:cases){
      RectF bounds=new RectF(10,20,310,520);
      buildPath(new Path(bounds),bounds,radii[0],radii[1],radii[2],radii[3]);
    }
    System.out.println("Bubble corner geometry passed (7 configurations)");
  }
}
'''.replace('METHOD', method)
        with tempfile.TemporaryDirectory(prefix='bubble-corners-', dir=os.environ.get('TMPDIR')) as temp:
            java = Path(temp) / 'DrawAlgorithms.java'
            java.write_text(fixture)
            subprocess.run(['javac', '-encoding', 'UTF-8', str(java)], check=True)
            subprocess.run(['java', '-cp', temp, 'DrawAlgorithms'], check=True)


if __name__ == '__main__':
    unittest.main()
