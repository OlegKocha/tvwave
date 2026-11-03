import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
public class QrDecode {
 public static void main(String[] args) throws Exception {
  BufferedImage image=ImageIO.read(new File(args[0]));
  int w=image.getWidth(),h=image.getHeight();
  RGBLuminanceSource source=new RGBLuminanceSource(w,h,image.getRGB(0,0,w,h,null,0,w));
  System.out.print(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText());
 }
}
