package com.zptioning.net.section03_Clone;

import android.util.Log;

import com.zptioning.net.MainActivity1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * ==============================================================================
 * Copyright:
 * Description: com.zption.basetest
 * Author: zption
 * Version: 1.0
 * Date: 2018/12/20 17:24
 * HistoryModified:
 * ==============================================================================
 */
public class TestClone {
    /**
     * 拷贝构造方法指的是该类的构造方法参数为该类的对象
     */
    public void ShallowCopy1() {
        Age age = new Age(20);

        Person p1 = new Person(age, "尼古拉斯");
        Person p2 = new Person(p1);
        // p1是尼古拉斯 20
        Log.i(MainActivity1.TAG, "p1是" + p1);
        // p2是尼古拉斯 20
        Log.i(MainActivity1.TAG, "p2是" + p2);


        //修改p1的各属性值，观察p2的各属性值是否跟随变化
        p1.name = "赵四";
        age.age = 99;
        // --->修改后的p1是赵四 99
        Log.i(MainActivity1.TAG, "--->修改后的p1是" + p1);
        // --->修改后的p2是尼古拉斯 99
        Log.i(MainActivity1.TAG, "--->修改后的p2是" + p2);
    }

    class Person {
        //两个属性值：分别代表值传递和引用传递
        public Age age;
        public String name;

        public Person(Age age, String name) {
            this.age = age;
            this.name = name;
        }

        //拷贝构造方法
        public Person(Person p) {
            this.name = p.name;
            this.age = p.age;
        }

        public String toString() {
            return this.name + " " + this.age;
        }
    }

    class Age {
        public int age;

        public Age(int age) {
            this.age = age;
        }

        public String toString() {
            return age + "";
        }
    }

    /*********************************************************************************************/
    /*********************************************************************************************/
    /*********************************************************************************************/

    /**
     * 通过重写clone()方法进行浅拷贝
     */
    public void ShallowCopy2() {
        Age age = new Age(20);
        Student stu1 = new Student("小呆比", age, 175);

        //通过调用重写后的clone方法进行浅拷贝
        Student stu2 = (Student) stu1.clone();
        Log.i(MainActivity1.TAG, stu1.toString());
        Log.i(MainActivity1.TAG, stu2.toString());

        //尝试修改stu1中的各属性，观察stu2的属性有没有变化
        stu1.name = "大傻子";
        //改变age这个引用类型的成员变量的值
        stu1.aage = new Age(200);
        //使用这种方式修改age属性值的话，stu2是不会跟着改变的。因为创建了一个新的Age类对象而不是改变原对象的实例值
        age.age = 99;
        stu1.length = 216;
        Log.i(MainActivity1.TAG, stu1.toString());
        Log.i(MainActivity1.TAG, stu2.toString());
    }


    class Student implements Cloneable {
        //学生类的成员变量（属性）,其中一个属性为类的对象
        private String name;
        private Age aage;
        private int length;

        //构造方法,其中一个参数为另一个类的对象
        public Student(String name, Age a, int length) {
            this.name = name;
            this.aage = a;
            this.length = length;
        }

        //设置输出的字符串形式
        public String toString() {
            return "姓名是： " + this.name
                    + "， 年龄为： " + this.aage
                    + ", 长度是： " + this.length;
        }

        //重写Object类的clone方法
        public Object clone() {
            Object obj = null;
            //调用Object类的clone方法，返回一个Object实例
            try {
                obj = super.clone();
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
            }
            return obj;
        }
    }

    /**********************************************************************************************/
    /**********************************************************************************************/
    /**********************************************************************************************/
    /**********************************************************************************************/
    public void DeepCopy1() {
        DAge dAge = new DAge(20);// 5157
        DStudent dStudent1 = new DStudent("小呆比", dAge, 175);// 5158

        // clone 浅拷贝
        DStudent dStudent2 = (DStudent) dStudent1.clone();// 5157
        Log.i(MainActivity1.TAG, dStudent1.toString());// 姓名是： 小呆比， 年龄为： 20, 长度是： 175
        Log.i(MainActivity1.TAG, dStudent2.toString());// 姓名是： 小呆比， 年龄为： 20, 长度是： 175

        /* 修改 1 中的属性*/
        dStudent1.name = "二笔";
        dAge.age = 100;
        dStudent1.length = 18;

        Log.i(MainActivity1.TAG, dStudent1.toString());// 二笔， 年龄为： 100, 长度是： 18
        Log.i(MainActivity1.TAG, dStudent2.toString());// 小呆比， 年龄为： 20, 长度是： 175

        dStudent1.aage = new DAge(110);
        dStudent2.aage = new DAge(120);

        dAge.age = -100;
        Log.i(MainActivity1.TAG, dStudent1.toString());// 二笔， 年龄为： 110, 长度是： 18
        Log.i(MainActivity1.TAG, dStudent2.toString());// 小呆比， 年龄为： 120, 长度是： 175
    }

    class DAge implements Cloneable {
        private int age;

        public DAge(int age) {
            this.age = age;
        }

        @Override
        public String toString() {
            return this.age + "";
        }

        public Object clone() {
            Object obj = null;
            try {
                obj = super.clone();
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
            }
            return obj;
        }
    }

    class DStudent implements Cloneable {
        private String name;
        private DAge aage;
        private int length;

        public DStudent(String name, DAge aage, int length) {
            this.name = name;
            this.aage = aage;
            this.length = length;
        }

        public String toString() {
            return "姓名是： " + this.name + "， 年龄为： "
                    + this.aage.toString() + ", 长度是： "
                    + this.length;
        }


        @Override
        protected Object clone() {
            Object obj = null;
            //调用Object类的clone方法——浅拷贝
            try {
                obj = super.clone();// obj 5181
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
            }
            //先将obj转化为学生类实例
            DStudent dStudent = (DStudent) obj;// 5175
            //调用Age类的clone方法进行深拷贝
            dStudent.aage = ((DAge) dStudent.aage.clone());// 5226

            return obj;
        }
    }

    /**********************************************************************************************/
    /**********************************************************************************************/
    /**********************************************************************************************/
    /**********************************************************************************************/

//    public void DeepCopy2() throws IOException, ClassNotFoundException {
    public void DeepCopy2() throws IOException, ClassNotFoundException {
        CAge a = new CAge(20);
        CStudent stu1 = new CStudent("摇头耶稣", a, 175);
        //通过序列化方法实现深拷贝
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(stu1);
        oos.flush();
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()));
        Student stu2 = (Student) ois.readObject();
        System.out.println(stu1.toString());
        System.out.println(stu2.toString());
        System.out.println();
        //尝试修改stu1中的各属性，观察stu2的属性有没有变化
        stu1.setName("大傻子");
        //改变age这个引用类型的成员变量的值
        a.setAge(99);
        stu1.setLength(216);
        System.out.println(stu1.toString());
        System.out.println(stu2.toString());
    }


    /*
     * 创建年龄类
     */
    class CAge implements Serializable {
        private static final long serialVersionUID = 3788596415215462716L;
        //年龄类的成员变量（属性）
        private int age;

        //构造方法
        public CAge(int age) {
            this.age = age;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public String toString() {
            return this.age + "";
        }
    }

    /*
     * 创建学生类
     */
    class CStudent implements Serializable {
        private static final long serialVersionUID = -2374717857094359581L;
        //学生类的成员变量（属性）,其中一个属性为类的对象
        private String name;
        private CAge mAage;
        private int length;

        //构造方法,其中一个参数为另一个类的对象
        public CStudent(String name, CAge a, int length) {
            this.name = name;
            this.mAage = a;
            this.length = length;
        }

        //eclipe中alt+shift+s自动添加所有的set和get方法
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public CAge getaAge() {
            return this.mAage;
        }

        public void setaAge(CAge CAge) {
            this.mAage = CAge;
        }

        public int getLength() {
            return this.length;
        }

        public void setLength(int length) {
            this.length = length;
        }

        //设置输出的字符串形式
        public String toString() {
            return "姓名是： " + this.getName() + "， 年龄为： " + this.getaAge().toString() + ", 长度是： " + this.getLength();
        }
    }
}