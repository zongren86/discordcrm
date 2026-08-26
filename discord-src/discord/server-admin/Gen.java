
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class Gen {
    public static void main(String[] a) {
        BCryptPasswordEncoder e = new BCryptPasswordEncoder();
        System.out.println(e.encode("123456"));
    }
}
